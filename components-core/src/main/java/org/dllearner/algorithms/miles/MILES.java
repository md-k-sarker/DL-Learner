/**
 * 
 */
package org.dllearner.algorithms.miles;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.Timer;
import java.util.TimerTask;

import org.dllearner.core.AbstractCELA;
import org.dllearner.core.AbstractReasonerComponent;
import org.dllearner.core.ComponentInitException;
import org.dllearner.core.owl.Description;
import org.dllearner.core.owl.Individual;
import org.dllearner.learningproblems.ClassLearningProblem;
import org.dllearner.learningproblems.PosNegLP;
import org.dllearner.learningproblems.PosNegLPStandard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import weka.core.Instances;

/**
 * First draft of a new kind of learning algorithm:
 * The basic idea is as follows: 
 * We take an existing learning algorithm which holds internally a search tree.
 * During the base algorithm run, we query periodically for nodes in the search tree,
 * and check if a linear combination gives better results.
 * @author Lorenz Buehmann
 *
 */
public class MILES {
	
	private static final Logger logger = LoggerFactory.getLogger(MILES.class);
	
	
	private AbstractCELA la;
	private PosNegLP lp;
	private AbstractReasonerComponent rc;
	
	// settings for the frequency
	private int delay = 0;
	private int period = 1000;
	
	// we can apply the base learning algorithm on a subset of the examples
	// and evaluate the combined solution on the rest of the data
	private boolean performInternalCV = true;
	private double sampleSize = 0.9;
	
	//

	public MILES(AbstractCELA la, PosNegLP lp, AbstractReasonerComponent rc) {
		this.la = la;
		this.lp = lp;
		this.rc = rc;
	}
	
	public MILES(AbstractCELA la, ClassLearningProblem lp, AbstractReasonerComponent rc) {
		this.la = la;
		this.rc = rc;
		
		// we convert to PosNegLP because we need at least the distinction between pos and neg examples
		// for the sampling
		// TODO do this in the PosNegLP constructor
		this.lp = new PosNegLPStandard(rc);
		SortedSet<Individual> posExamples = rc.getIndividuals(lp.getClassToDescribe());
		Set<Individual> negExamples = Sets.difference(rc.getIndividuals(), posExamples);
		this.lp.setPositiveExamples(posExamples);
		this.lp.setNegativeExamples(negExamples);
	}
	
	public void start(){
		// if enabled, we split the data into a train and a test set
		if(performInternalCV){
			List<Individual> posExamples = new ArrayList<Individual>(lp.getPositiveExamples());
			List<Individual> negExamples = new ArrayList<Individual>(lp.getNegativeExamples());
			
			// pos example subsets
			int trainSizePos = (int) (0.9 * posExamples.size());
			List<Individual> posExamplesTrain = posExamples.subList(0, trainSizePos);
			List<Individual> posExamplesTest = posExamples.subList(trainSizePos, posExamples.size());
			
			// neg example subsets
			int trainSizeNeg = (int) (0.9 * negExamples.size());
			List<Individual> negExamplesTrain = negExamples.subList(0, trainSizeNeg);
			List<Individual> negExamplesTest = negExamples.subList(trainSizeNeg, negExamples.size());
			
			lp.setPositiveExamples(new HashSet<Individual>(posExamplesTrain));
			lp.setNegativeExamples(new HashSet<Individual>(negExamplesTrain));
			
			// TODO replace by 	
						//FoldGenerator<Individual> foldGenerator = new FoldGenerator<Individual>(lp.getPositiveExamples(), lp.getNegativeExamples());
			
			try {
				lp.init();
			} catch (ComponentInitException e) {
				e.printStackTrace();
			}
		}
		
		// 1. start the base learning algorithm in a separate thread
		Thread t = new Thread(new Runnable() {
			
			@Override
			public void run() {
				la.start();
			}
		});
		t.start();
		
		// 2. each x seconds get the top n concepts and validate the linear combination
		Timer timer = new Timer();
		timer.schedule(new LinearClassificationTask(), delay, period);
		
		try {
			t.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	
	class LinearClassificationTask extends TimerTask {
		
		private DescriptionLinearClassifier classifier;

		public LinearClassificationTask() {
			classifier = new DescriptionLinearClassifier(lp, rc);
		}
		
		public void run() {
			logger.debug("Computing linear combination...");
			long start = System.currentTimeMillis();
			List<Description> descriptions = la.getCurrentlyBestDescriptions(5);
			classifier.getLinearCombination(descriptions); 
			long end = System.currentTimeMillis();
			if (logger.isDebugEnabled()) {
				logger.debug("Operation took " + (end - start) + "ms");
			}
		}
	}
}