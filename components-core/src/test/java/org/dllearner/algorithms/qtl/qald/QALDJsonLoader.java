package org.dllearner.algorithms.qtl.qald;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Sets;
import org.aksw.jena_sparql_api.cache.core.QueryExecutionFactoryCacheEx;
import org.aksw.jena_sparql_api.cache.extra.CacheFrontend;
import org.aksw.jena_sparql_api.cache.extra.CacheFrontendImpl;
import org.aksw.jena_sparql_api.cache.h2.CacheCoreH2;
import org.aksw.jena_sparql_api.cache.h2.CacheUtilsH2;
import org.aksw.jena_sparql_api.core.FluentQueryExecutionFactory;
import org.aksw.jena_sparql_api.core.QueryExecutionFactory;
import org.aksw.jena_sparql_api.http.QueryExecutionHttpWrapper;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.WebContent;
import org.apache.jena.sparql.engine.http.QueryEngineHTTP;
import org.apache.jena.sparql.vocabulary.FOAF;
import org.dllearner.algorithms.qtl.QueryTreeUtils;
import org.dllearner.algorithms.qtl.datastructures.impl.RDFResourceTree;
import org.dllearner.algorithms.qtl.impl.QueryTreeFactory;
import org.dllearner.algorithms.qtl.impl.QueryTreeFactoryBase;
import org.dllearner.algorithms.qtl.impl.QueryTreeFactoryBaseInv;
import org.dllearner.algorithms.qtl.operations.lgg.LGGGenerator;
import org.dllearner.algorithms.qtl.operations.lgg.LGGGeneratorSimple;
import org.dllearner.algorithms.qtl.qald.schema.Binding;
import org.dllearner.algorithms.qtl.qald.schema.QALDJson;
import org.dllearner.algorithms.qtl.qald.schema.Question;
import org.dllearner.algorithms.qtl.util.StopURIsDBpedia;
import org.dllearner.algorithms.qtl.util.StopURIsOWL;
import org.dllearner.algorithms.qtl.util.StopURIsRDFS;
import org.dllearner.algorithms.qtl.util.filters.NamespaceDropStatementFilter;
import org.dllearner.algorithms.qtl.util.filters.ObjectDropStatementFilter;
import org.dllearner.algorithms.qtl.util.filters.PredicateDropStatementFilter;
import org.dllearner.kb.sparql.ConciseBoundedDescriptionGenerator;
import org.dllearner.kb.sparql.ConciseBoundedDescriptionGeneratorImpl;
import org.dllearner.kb.sparql.SparqlEndpoint;
import org.dllearner.kb.sparql.SymmetricConciseBoundedDescriptionGeneratorImpl;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author Lorenz Buehmann
 */
public class QALDJsonLoader {

	public static List<Question> loadQuestions(File file) {
		try(InputStream is = new FileInputStream(file)){
			ObjectMapper mapper = new ObjectMapper();

			QALDJson qald = mapper.readValue(is, QALDJson.class);
			return qald.getQuestions();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

		return null;
	}

	public static void main(String[] args) throws Exception {
		List<Question> questions = QALDJsonLoader.loadQuestions(
				new File("/home/me/work/experiments/qtl/qald/qald-6-train-multilingual.json"));

		List<Question> filteredQuestions = questions.stream()
				.filter(q -> !q.isAggregation()) // no aggregation
				.filter(q -> q.getAnswertype().equals("resource")) // only resources
				.filter(q -> !q.getAnswers().isEmpty()) // skip no answers
				.filter(q -> q.getAnswers().get(0).getResults().getBindings().size() >= 2) // result size >= 2
				.sorted((q1, q2) -> Integer.compare(q1.getId(), q2.getId()))
				.collect(Collectors.toList());

		int maxDepth = 1;

//		QueryTreeFactory factory = new QueryTreeFactoryBase();
		QueryTreeFactory factory = new QueryTreeFactoryBaseInv();
		factory.setMaxDepth(maxDepth);
		factory.addDropFilters(
				new PredicateDropStatementFilter(
						Sets.union(Sets.union(StopURIsDBpedia.get(), StopURIsRDFS.get()), StopURIsOWL.get())),
				new ObjectDropStatementFilter(StopURIsOWL.get()),
				new NamespaceDropStatementFilter(
						Sets.newHashSet(
								"http://dbpedia.org/property/",
								"http://purl.org/dc/terms/",
								"http://dbpedia.org/class/yago/",
								FOAF.getURI()
						)
				)
		);
		SparqlEndpoint endpoint = SparqlEndpoint.getEndpointDBpedia();
		QueryExecutionFactory qef = FluentQueryExecutionFactory
				.http(endpoint.getURL().toString(), endpoint.getDefaultGraphURIs())
				.config().withPostProcessor(qe -> ((QueryEngineHTTP) ((QueryExecutionHttpWrapper) qe).getDecoratee())
						.setModelContentType(WebContent.contentTypeRDFXML))
				.end()
				.create();
		long timeToLive = TimeUnit.DAYS.toMillis(30);
		CacheFrontend cacheFrontend = CacheUtilsH2.createCacheFrontend("/tmp/qald/sparql", true, timeToLive);
		qef = new QueryExecutionFactoryCacheEx(qef, cacheFrontend);

//		ConciseBoundedDescriptionGenerator cbdGen = new ConciseBoundedDescriptionGeneratorImpl(qef);
		ConciseBoundedDescriptionGenerator cbdGen = new SymmetricConciseBoundedDescriptionGeneratorImpl(qef);

		LGGGenerator lggGen = new LGGGeneratorSimple();

		filteredQuestions.stream()
				.filter(q -> q.getQuery().getSparql().contains("Goofy"))
				.forEach(q -> {
			System.out.println(q.getQuery().getSparql());
			List<Binding> bindings = q.getAnswers().get(0).getResults().getBindings();

			List<RDFResourceTree> trees = new ArrayList<>();
			bindings.forEach(b -> {
				String uri = b.getUri().getValue();
				Model cbd = cbdGen.getConciseBoundedDescription(uri, maxDepth);
				RDFResourceTree tree = factory.getQueryTree(uri, cbd);
//				System.out.println(tree.getStringRepresentation());
				trees.add(tree);
			});

			RDFResourceTree lgg = lggGen.getLGG(trees);
			System.out.println(lgg.getStringRepresentation());
			System.out.println(QueryTreeUtils.toSPARQLQuery(lgg));
		});

	}
}