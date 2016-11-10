/**
 * Copyright (C) 2007 - 2016, Jens Lehmann
 *
 * This file is part of DL-Learner.
 *
 * DL-Learner is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * DL-Learner is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.dllearner.algorithms.qtl;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.jamonapi.MonitorFactory;
import org.aksw.jena_sparql_api.core.QueryExecutionFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.dllearner.algorithms.qtl.datastructures.impl.EvaluatedRDFResourceTree;
import org.dllearner.algorithms.qtl.datastructures.impl.RDFResourceTree;
import org.dllearner.algorithms.qtl.impl.QueryTreeFactory;
import org.dllearner.algorithms.qtl.impl.QueryTreeFactoryBase;
import org.dllearner.algorithms.qtl.operations.lgg.LGGGenerator;
import org.dllearner.algorithms.qtl.operations.lgg.LGGGeneratorSimple;
import org.dllearner.core.*;
import org.dllearner.core.StringRenderer.Rendering;
import org.dllearner.core.config.ConfigOption;
import org.dllearner.kb.SparqlEndpointKS;
import org.dllearner.kb.sparql.ConciseBoundedDescriptionGenerator;
import org.dllearner.kb.sparql.ConciseBoundedDescriptionGeneratorImpl;
import org.dllearner.learningproblems.PosNegLP;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.util.SimpleShortFormProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.text.DecimalFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

@ComponentAnn(name="query tree learner with noise (disjunctive)", shortName="qtl2dis", version=0.8)
public class QTL3 implements StoppableLearningAlgorithm, SparqlQueryLearningAlgorithm{

	private static final Logger logger = LoggerFactory.getLogger(QTL3.class);
	private final DecimalFormat dFormat = new DecimalFormat("0.00");

	private SparqlEndpointKS ks;

//	private LGGGenerator2 lggGenerator = new LGGGeneratorSimple();
	private LGGGenerator lggGenerator;

	private QueryTreeFactory treeFactory;
	private ConciseBoundedDescriptionGenerator cbdGen;

	private Queue<EvaluatedRDFResourceTree> todoList;
	private SortedSet<EvaluatedRDFResourceTree> currentPartialSolutions;

	private double bestCurrentScore = 0d;

	private List<RDFResourceTree> currentPosExampleTrees = new ArrayList<>();
	private List<RDFResourceTree> currentNegExampleTrees = new ArrayList<>();
	private Set<OWLIndividual> currentPosExamples = new HashSet<>();
	private Set<OWLIndividual> currentNegExamples = new HashSet<>();

	private BiMap<RDFResourceTree, OWLIndividual> tree2Individual = HashBiMap.create();

	private SPARQLQueryLearningProblemPosNeg lp;
	private Model model;

	private volatile boolean stop;
	private boolean isRunning;

	private List<EvaluatedRDFResourceTree> partialSolutions;

	private EvaluatedDescription<? extends Score> currentBestSolution;

	@ConfigOption(defaultValue = "10", description = "maximum execution of the algorithm in seconds")
	protected long maxExecutionTimeInSeconds = 10;


	private int expressionTests = 0;

	// the time needed until the best solution was found
	private long timeBestSolutionFound = -1;

	private QueryExecutionFactory qef;

	private int maxTreeDepth = 2;

	private long nanoStartTime;

	public QTL3() {}

	public QTL3(SPARQLQueryLearningProblemPosNeg lp, QueryExecutionFactory qef) {
		this.lp = lp;
		this.qef = qef;
	}



	/* (non-Javadoc)
	 * @see org.dllearner.core.Component#init()
	 */
	@Override
	public void init() throws ComponentInitException {
		logger.info("Initializing...");

		// get query execution factory from KS
		if(qef == null) {
			qef = ks.getQueryExecutionFactory();
		}

		if(treeFactory == null) {
			treeFactory = new QueryTreeFactoryBase();
		}
		cbdGen = new ConciseBoundedDescriptionGeneratorImpl(qef);

		lggGenerator = new LGGGeneratorSimple();

		// generate the query trees
		generateQueryTrees();

		//console rendering of class expressions
		StringRenderer.setRenderer(Rendering.MANCHESTER_SYNTAX);
		StringRenderer.setShortFormProvider(new SimpleShortFormProvider());

		//compute the LGG for all examples
		//this allows us to prune all other trees because we can omit paths in trees which are contained in all positive
		//as well as negative examples
//		List<RDFResourceTree> allExamplesTrees = new ArrayList<RDFResourceTree>();
//		allExamplesTrees.addAll(currentPosExampleTrees);
//		allExamplesTrees.addAll(currentNegExampleTrees);
//		RDFResourceTree lgg = lggGenerator.getLGG(allExamplesTrees);
//		lgg.dump();
		logger.info("...initialization finished.");
	}

	private void generateQueryTrees(){
		logger.info("Generating trees...");
		RDFResourceTree queryTree;

		// positive examples
		for (List<String> tuple : lp.getPosExamples()) {
			try {

				// get the CBD for each resource in the tuple
				Model m = ModelFactory.createDefaultModel();
				tuple.forEach(r -> {
					Model cbd = cbdGen.getConciseBoundedDescription(r, maxTreeDepth);

					m.add(cbd);
				});

//				queryTree = treeFactory.getQueryTree(ind.toStringID(), cbd, maxTreeDepth);

			} catch (Exception e) {
//				logger.error("Failed to generate tree for resource " + ind, e);
				throw new RuntimeException();
			}
		}

		logger.info("...done.");
	}

	/* (non-Javadoc)
	 * @see org.dllearner.core.LearningAlgorithm#start()
	 */
	@Override
	public void start() {
		if(currentPosExampleTrees.isEmpty()) {
			logger.info("No positive examples given!");
			return;
		}

		isRunning = true;

		logger.info("Running...");

		reset();


		isRunning = false;

		long nanoEndTime = System.nanoTime();
		logger.info("Finished in " + TimeUnit.NANOSECONDS.toMillis(nanoEndTime - nanoStartTime) + "ms.");
		logger.info(expressionTests +" descriptions tested");
		if(currentBestSolution != null) {
			logger.info("Combined solution:" + currentBestSolution.getDescription().toString().replace("\n", ""));
			logger.info(currentBestSolution.getScore().toString());
		} else {
			logger.info("Could not find a solution in the given time.");
		}
	}

	@Override
	public LearningProblem getLearningProblem() {
		return null;
	}

	private void reset(){
		stop = false;
		isRunning = true;

		currentBestSolution = null;
		partialSolutions = new ArrayList<>();

		MonitorFactory.getTimeMonitor("lgg").reset();
		nanoStartTime = System.nanoTime();
	}

	/* (non-Javadoc)
	 * @see org.dllearner.core.StoppableLearningAlgorithm#stop()
	 */
	@Override
	public void stop() {
		stop = true;
	}

	@Override
	public void setLearningProblem(LearningProblem learningProblem) {

	}

	/* (non-Javadoc)
		 * @see org.dllearner.core.StoppableLearningAlgorithm#isRunning()
		 */
	@Override
	public boolean isRunning() {
		return isRunning;
	}

	/**
	 * Computes all trees from the given list {@code allTrees} which are subsumed by {@code tree}.
	 * @param tree the tree
	 * @param trees all trees
	 * @return all trees from the given list {@code allTrees} which are subsumed by {@code tree}
	 */
	private List<RDFResourceTree> getCoveredTrees(RDFResourceTree tree, List<RDFResourceTree> trees){
		List<RDFResourceTree> coveredTrees = new ArrayList<>();
		for (RDFResourceTree queryTree : trees) {
			if(QueryTreeUtils.isSubsumedBy(queryTree, tree)){
				coveredTrees.add(queryTree);
			}
		}
		return coveredTrees;
	}

	/**
	 * Computes all trees from the given list {@code trees} which are not subsumed by {@code tree}.
	 * @param tree the tree
	 * @param trees the trees
	 * @return all trees from the given list {@code trees} which are not subsumed by {@code tree}.
	 */
	private List<RDFResourceTree> getUncoveredTrees(RDFResourceTree tree, List<RDFResourceTree> trees){
		List<RDFResourceTree> uncoveredTrees = new ArrayList<>();
		for (RDFResourceTree queryTree : trees) {
			if(!QueryTreeUtils.isSubsumedBy(queryTree, tree)){
				uncoveredTrees.add(queryTree);
			}
		}
		return uncoveredTrees;
	}

	private boolean terminationCriteriaSatisfied() {
		//stop was called or time expired
		if(stop || isTimeExpired()){
			return true;
		}

		return false;
	}

	/**
	 * @param treeFactory the treeFactory to set
	 */
	public void setTreeFactory(QueryTreeFactory treeFactory) {
		this.treeFactory = treeFactory;
	}
	
	public EvaluatedRDFResourceTree getBestSolution(){
		return currentPartialSolutions.last();
	}
	
	public SortedSet<EvaluatedRDFResourceTree> getSolutions(){
		return currentPartialSolutions;
	}
	
	public List<EvaluatedRDFResourceTree> getSolutionsAsList(){
		//		Collections.sort(list, Collections.reverseOrder());
		return new ArrayList<>(currentPartialSolutions);
	}
	
	/**
	 * @param positiveExampleTrees the positive example trees to set
	 */
	public void setPositiveExampleTrees(Map<OWLIndividual,RDFResourceTree> positiveExampleTrees) {
		this.currentPosExampleTrees = new ArrayList<>(positiveExampleTrees.values());
		this.currentPosExamples = new HashSet<>(positiveExampleTrees.keySet());
		
		for (Entry<OWLIndividual, RDFResourceTree> entry : positiveExampleTrees.entrySet()) {
			OWLIndividual ind = entry.getKey();
			RDFResourceTree tree = entry.getValue();
			tree2Individual.put(tree, ind);
		}
	}
	
	/**
	 * @param negativeExampleTrees the negative example trees to set
	 */
	public void setNegativeExampleTrees(Map<OWLIndividual,RDFResourceTree> negativeExampleTrees) {
		this.currentNegExampleTrees = new ArrayList<>(negativeExampleTrees.values());
		this.currentNegExamples = new HashSet<>(negativeExampleTrees.keySet());
		
		for (Entry<OWLIndividual, RDFResourceTree> entry : negativeExampleTrees.entrySet()) {
			OWLIndividual ind = entry.getKey();
			RDFResourceTree tree = entry.getValue();
			tree2Individual.put(tree, ind);
		}
	}

	protected long getCurrentRuntimeInMilliSeconds() {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanoStartTime);
	}

	protected boolean isTimeExpired() {
		return getCurrentRuntimeInMilliSeconds() >= TimeUnit.SECONDS.toMillis(maxExecutionTimeInSeconds);
	}
	
	/**
	 * @param ks the ks to set
	 */
	@Autowired
	public void setKs(SparqlEndpointKS ks) {
		this.ks = ks;
	}
	
	/**
	 * @param maxTreeDepth the maximum depth of the trees, if those have to be generated
	 * first. The default depth is 2.
	 */
	public void setMaxTreeDepth(int maxTreeDepth) {
		this.maxTreeDepth = maxTreeDepth;
	}
	
	/**
	 * @return the runtime in ms until the best solution was found
	 */
	public long getTimeBestSolutionFound() {
		return timeBestSolutionFound;
	}

	@Override
	public List<String> getCurrentlyBestSPARQLQueries(int nrOfSPARQLQueries) {
		return null;
	}

	@Override
	public String getBestSPARQLQuery() {
		return null;
	}
}