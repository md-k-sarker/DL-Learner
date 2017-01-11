/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.dllearner.algorithms.probabilistic.parameter.unife.edge;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import org.apache.log4j.Logger;
import org.dllearner.core.AbstractReasonerComponent;
import org.dllearner.core.ComponentAnn;
import org.dllearner.core.ComponentInitException;
import org.dllearner.core.KnowledgeSource;
import org.dllearner.core.ReasoningMethodUnsupportedException;
import org.dllearner.core.config.ConfigOption;
import org.dllearner.core.probabilistic.unife.ParameterLearningException;
import org.dllearner.kb.OWLAPIOntology;
import org.dllearner.learningproblems.ClassLearningProblem;
import org.dllearner.reasoning.ClosedWorldReasoner;
import org.dllearner.reasoning.OWLAPIReasoner;
import org.mindswap.pellet.utils.Timer;
import org.mindswap.pellet.utils.Timers;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import unife.bundle.utilities.BundleUtilities;
import static unife.bundle.utilities.BundleUtilities.getManchesterSyntaxString;
import unife.math.ApproxDouble;
import unife.edge.utilities.EDGEUtilities;
import unife.exception.IllegalValueException;
import unife.math.utilities.MathUtilities;
import unife.utilities.GeneralUtils;

/**
 *
 * @author Giuseppe Cota <giuseppe.cota@unife.it>
 */
@ComponentAnn(name = "DummyParameterLearner", shortName = "dummypl", version = 1.0)
public class DummyParameterLearner extends AbstractEDGE {

    @ConfigOption(defaultValue = "0.4",
            required = false,
            description = "Value of the fixed probability. All the probabilistic "
            + "axioms will have the same probability")
    private double fixedProbability = 0.4;

    private static Logger logger
            = Logger.getLogger(DummyParameterLearner.class.getName());

    Map<OWLAxiom, ApproxDouble> pMap;

    public DummyParameterLearner() {

    }

    public DummyParameterLearner(ClassLearningProblem lp, Set<OWLAxiom> targetAxioms) {
        super(lp, targetAxioms);
    }

    /**
     * Get the Log-Likelihood of all the examples/queries.
     *
     * @return the log-likelihood of all the examples/queries
     */
    public ApproxDouble getLL() {
        return ApproxDouble.zero();
    }

    @Override
    public Map<String, Long> getTimeMap() {
        Map<String, Long> dummyTimers = new HashMap<>();
        dummyTimers.put("DummyParameterLearner", 0L);
        return dummyTimers;
    }

    @Override
    public void init() throws ComponentInitException {
        AbstractReasonerComponent rc = learningProblem.getReasoner();
        if (rc instanceof ClosedWorldReasoner) {
            sourcesOntology = ((ClosedWorldReasoner) rc).getReasonerComponent().getOntology();
        } else if (rc instanceof OWLAPIReasoner) {
            sourcesOntology = ((OWLAPIReasoner) rc).getOntology();
        } else {
            throw new ComponentInitException("Unsupported Reasoning: "
                    + rc.getClass());
        }
    }

    @Override
    public ApproxDouble getParameter(OWLAxiom ax) throws ParameterLearningException {

        ApproxDouble parameter;
        for (OWLAxiom axiom : pMap.keySet()) {
            if (axiom.equalsIgnoreAnnotations(ax)) {
                parameter = pMap.get(axiom);
                return parameter;
            }
        }
        return null;
    }

    public OWLOntology getLearnedOntology() {
        Timers timers = new Timers();
        Timer timer = timers.createTimer("OntologyCreation");

        timer.start();
        OWLOntologyManager owlManager = sourcesOntology.getOWLOntologyManager();
        OWLDataFactory owlFactory = owlManager.getOWLDataFactory();

        for (OWLAxiom ax : sourcesOntology.getLogicalAxioms()) {

            for (OWLAxiom pax : GeneralUtils.safe(pMap).keySet()) {

                if (pax.equalsIgnoreAnnotations(ax)) {
                    Set<OWLAnnotation> axAnnotations = new HashSet(ax.getAnnotations());
                    if (ax.getAnnotations(BundleUtilities.PROBABILISTIC_ANNOTATION_PROPERTY).size() > 0) {
                        for (OWLAnnotation ann : axAnnotations) {
                            if (ann.getProperty().equals(BundleUtilities.PROBABILISTIC_ANNOTATION_PROPERTY)) {
                                axAnnotations.remove(ann);
                                break;
                            }
                        }
                    }

                    owlManager.removeAxiom(sourcesOntology, ax);

                    axAnnotations.add(owlFactory.getOWLAnnotation(BundleUtilities.PROBABILISTIC_ANNOTATION_PROPERTY, owlFactory.getOWLLiteral(pMap.get(pax).getValue())));
                    owlManager.addAxiom(sourcesOntology, pax.getAnnotatedAxiom(axAnnotations));
                    //ontologyAxioms.remove(pax);
                    break;
                }
            }
        }

        timer.stop();

        logger.info("Successful creation of the learned ontology");
        logger.info("Ontology created in " + timer.getAverage() + " (ms)");

        return sourcesOntology;

    }

    public void changeSourcesOntology(OWLOntology ontology) {
        sourcesOntology = ontology;
        learningProblem.getReasoner().changeSources(Collections.singleton((KnowledgeSource) new OWLAPIOntology(ontology)));
//        learningProblem.getReasoner().changeSources(Collections.singleton((KnowledgeSource) ontology));
    }

    public void reset() {
        isRunning = false;
        stop = false;
    }

    public ApproxDouble getLOGZERO() {
        return new ApproxDouble(Math.log(0.000001));
    }

    @Override
    public void start() {
        isRunning = true;
        stop = false;

        SortedSet<OWLAxiom> axioms = EDGEUtilities.get_ax_filtered(sourcesOntology);

        pMap = new HashMap<>();

        Random probGenerator = new Random();

        if (randomize) {
            probGenerator.setSeed(seed);
            logger.debug("Random Seed set to: " + seed);
        }

        for (OWLAxiom axiom : axioms) {
            List<ApproxDouble> probList = new ArrayList<>();
            String axiomName = getManchesterSyntaxString(axiom);

            if (probabilizeAll) {
                double probValue;
                if (randomize) {
                    probValue = probGenerator.nextDouble();
                } else {
                    probValue = getFixedProbability();
                }
                probList.add(new ApproxDouble(probValue));

            } else {

                for (OWLAnnotation annotation : axiom.getAnnotations(BundleUtilities.PROBABILISTIC_ANNOTATION_PROPERTY)) {

                    // metodo per aggiungere coppia assioma/probabilita alla Map
                    if (annotation.getValue() != null) {

                        ApproxDouble annProbability;
                        if (randomize) {
                            annProbability = new ApproxDouble(probGenerator.nextDouble());
                            probList.add(annProbability);
                        } else {
                            annProbability = new ApproxDouble(getFixedProbability());
                            probList.add(annProbability);
                        }
                        if (showAll) {
//                            System.out.print(axiomName);
                            String str = " => " + annProbability;
//                            System.out.println(str);
                            logger.info(axiomName + str);
                        }
                    }
                }
            }
            if (probList.size() > 0) {
                OWLAxiom pMapAxiom = axiom.getAxiomWithoutAnnotations();
                ApproxDouble varProbTemp = new ApproxDouble(ApproxDouble.zero());
                if (pMap.containsKey(pMapAxiom)) {
                    varProbTemp = pMap.get(pMapAxiom);
                }
                for (ApproxDouble ithProb : probList) {
                    ApproxDouble mul = varProbTemp.multiply(ithProb);
//                    varProbTemp = varProbTemp._add(ithProb)._subtract(mul);
                    varProbTemp._add(ithProb)._subtract(mul);
                }
                pMap.put(pMapAxiom, varProbTemp);
            }

        }

        if (probabilizeAll) {
//            System.out.println("Created " + axioms.size() + " probabilistic axiom");
            logger.info("Created " + axioms.size() + " probabilistic axiom");
        }

        if (pMap.size() > 0) {
//            System.out.println("ApproxDouble Map computed. Size: " + pMap.size());
            logger.info("Probability Map computed. Size: " + pMap.size());
        } else {
//            System.out.println("ApproxDouble Map computed. Size: " + pMap.size());
            logger.info("Probability Map is empty");
        }

        isRunning = false;
        stop = true;
    }

    /**
     * @return the fixedProbability
     */
    public double getFixedProbability() {
        return fixedProbability;
    }

    /**
     * @param fixedProbability the fixedProbability to set
     */
    public void setFixedProbability(double fixedProbability) {
        this.fixedProbability = fixedProbability;
    }

}
