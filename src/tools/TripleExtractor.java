package tools;
import edu.stanford.nlp.ie.util.RelationTriple;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.naturalli.NaturalLogicAnnotations;
import edu.stanford.nlp.util.CoreMap;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;


public class TripleExtractor {

	private static final String ANNOTATORS = "tokenize,ssplit,pos,depparse,lemma,natlog,ner,mention,coref,openie";
	private static final String COREF = "true";
	private static final String STRICT = "true";
	private static final String ALL_NOMINAL = "true";
	private static final String IGNORE_AFFINITY = "true";
	private Properties props;
	private StanfordCoreNLP pipeline;
	
	
	public TripleExtractor(){
		// Create the Stanford CoreNLP pipeline
		this.props = new Properties();
		this.props.setProperty("openie.ignore_affinity", IGNORE_AFFINITY);
		this.props.setProperty("openie.triple.strict", STRICT);
		this.props.setProperty("openie.triple.all_nominals", ALL_NOMINAL);
		this.props.setProperty("openie.resolve_coref", COREF);
		this.props.setProperty("annotators", ANNOTATORS);
		this.pipeline = new StanfordCoreNLP(props);
	}
	
	private List<RelationTriple> getTriples(String text){
		// Annotate an example document.
		Annotation doc = new Annotation(text);
		List<RelationTriple> triples = new LinkedList<>();
		try{
			pipeline.annotate(doc);
		}catch (NullPointerException e) {
			/* alcune delle frasi creano problemi al parser facendogli restituire NullPointerException;
			   sembrerebbe essere un problema del codice sorgente di corenlp dato che le frasi problematiche non 
			   hanno particolari caratteri nè sono strutturate in maniera anomala;
			   dato che succede molto di rado, per adesso vengono semplicemente ignorate */
            return triples;
        }

		// Loop over sentences in the document
		for (CoreMap sentence : doc.get(CoreAnnotations.SentencesAnnotation.class))
			// Get the OpenIE triples for the sentence
			triples.addAll(sentence.get(NaturalLogicAnnotations.RelationTriplesAnnotation.class));
		
		return triples;
	}

	
	public String getRelevantTriples(HashSet<String> lo1s, HashSet<String> lo2s, List<String> sentences, String classification){
		List<RelationTriple> triples = new LinkedList<>();
		String relevantTriples = "";

		//prendo le triple per ogni frase
		for(String sentence : sentences)
			if(sentence != null)
				triples.addAll(getTriples(sentence));

		//estraggo le features
		for (RelationTriple triple : triples){
			boolean lo1IsSubject = false, lo2IsObject = false;

			//guardo se il soggetto o l'oggetto della tripla è LO1
			for(String lo1 : lo1s)
				if(lo1.contains(triple.subjectLemmaGloss()))
					lo1IsSubject = true;

			//guardo se il soggetto o l'oggetto della tripla è LO2
			for(String lo2 : lo2s)
				if(lo2.contains(triple.objectLemmaGloss()))
					lo2IsObject = true;

			//aggiorno i contatori delle features
			if((lo1IsSubject && lo2IsObject))
				relevantTriples += classification + "\t" + triple.subjectLemmaGloss() + "\t" +
						triple.relationLemmaGloss() + "\t" + triple.objectLemmaGloss() + "\n";
		}
		
		return relevantTriples;
	}
	
	/*Usato solo per fare qualche test */
	 public static void main(String[] args) throws Exception {
		// Create the Stanford CoreNLP pipeline
		Properties props = new Properties();
		props.setProperty("openie.ignore_affinity", IGNORE_AFFINITY);
		props.setProperty("openie.triple.strict", STRICT);
		props.setProperty("openie.triple.all_nominals", ALL_NOMINAL);
		props.setProperty("openie.resolve_coref", COREF);
		props.setProperty("annotators", ANNOTATORS);
		StanfordCoreNLP pipeline = new StanfordCoreNLP(props);

//		byte[] encoded = Files.readAllBytes(Paths.get("./dataset/tmp/sentences.txt"));
//		String file =  new String(encoded, StandardCharsets.UTF_8);
//		String[] lines = file.split("\n");
//		for(int i = 0; i<lines.length-1; i++){
//			System.out.println(i+"\t\t"+lines[i]);
//			Annotation doc = new Annotation(lines[i]);
//			pipeline.annotate(doc);
//		}
		String s1 = "Look back at the method in the Shape class for changing the color of a shape:void setColor(Color newColor)";
		String s2 =  " {    color = newColor; // change value of instance variable    redraw();"
				+ " // redraw shape, which will appear in new color }A redraw message is sent here, but which object is it sent to? ";
		String s3 =  "Well, the setColor method is itself a message that was sent to some object";
		Annotation doc = new Annotation(s3);
		pipeline.annotate(doc);

		// Loop over sentences in the document
		for (CoreMap sentence : doc.get(CoreAnnotations.SentencesAnnotation.class)) {
			// Get the OpenIE triples for the sentence
			Collection<RelationTriple> triples = sentence.get(NaturalLogicAnnotations.RelationTriplesAnnotation.class);
			// Print the triples
			for (RelationTriple triple : triples) {
				System.out.println(triple.confidence + "\t" +
						triple.subjectLemmaGloss() + "\t" +
						triple.relationLemmaGloss() + "\t" +
						triple.objectLemmaGloss());
			}
		}
	}

}

