package tools;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import edu.stanford.nlp.ling.CoreAnnotations.LemmaAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.SentencesAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TokensAnnotation;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.util.CoreMap;

public class Dictionary {
	//path al dataset completo
	private static final String DATASET_PATH = "./dataset/fullDataset/";
	
	//mappa dominio -> numero di termini contenuti in esso
	private Map<String, Integer> domains = new TreeMap<>();
	//mappa termine(lemma) -> [n° di volte che compare in ogni dominio]
	private Map<String, Map<String, Integer>> lemmaFrequency = new TreeMap<>();
	//mappa termine(lemma) -> valore tf-idf per ogni dominio
	private Map<String, Map<String, Double>> tf_idf = new HashMap<>();
	

	public Dictionary(){
	}
	
	//pulisce il testo eliminando tutti i caratteri non alfanumerici e convertendo ogni termine in lemma
	private List<CoreMap> getCleanSentences(String text){
		String cleanText = text.replaceAll("[^a-zA-Z\\d\\s\\-]", " ").replaceAll(" +", " ");
		
		//sottopongo il testo al processo di lemmatizzazione
		Properties props;
        props = new Properties();
        props.put("annotators", "tokenize, ssplit, pos, lemma");
        StanfordCoreNLP pipeline = new StanfordCoreNLP(props);
        Annotation document = new Annotation(cleanText);
        pipeline.annotate(document);

        List<CoreMap> sentences = document.get(SentencesAnnotation.class);
        
		return sentences;
	}
	
	//aggiorna i vari conteggi di termini
	private void updateDomainInfo(String domain, List<CoreMap> sentences){
		for(CoreMap sentence: sentences) {
            // itero su tutti i token della frase
            for (CoreLabel token: sentence.get(TokensAnnotation.class)) {
                // prendo il lemma relativo alla parola
                String lemma = token.get(LemmaAnnotation.class);
                //aggiorno il conteggio dei termini per il dominio
                int newCount = this.domains.get(domain) +1;
                this.domains.put(domain, newCount);
                //aggiorno il conteggio del termine
                if(this.lemmaFrequency.containsKey(lemma)){
                	Map<String, Integer> domainCount = this.lemmaFrequency.get(lemma);
                	if(domainCount.containsKey(domain)){
                		int newLemmaCount = domainCount.get(domain) +1 ;
                		domainCount.put(domain, newLemmaCount);
                	} else
                		domainCount.put(domain, 1);
                } else {
                	Map<String, Integer> domainCount = new TreeMap<>();
                	domainCount.put(domain, 1);
                	this.lemmaFrequency.put(lemma, domainCount);
                }
            }
        }
	}
	
	//inizializza il dizionario per il calcolo del tf-idf leggendo i file di input
	public void prepareDictionary(){
		File[] directories = new File(DATASET_PATH).listFiles(File::isDirectory);

		for(File directory : directories){
			this.domains.put(directory.getName(), 0);
			File[] listOfDirectories = directory.listFiles(File::isDirectory);

			for(File subdirectory : listOfDirectories){
				File[] listOfFiles = subdirectory.listFiles(File::isFile);

				for(File f : listOfFiles)
					try {
						byte[] encoded = Files.readAllBytes(Paths.get(f.toURI()));
						String text = new String(encoded, StandardCharsets.UTF_8);
						List<CoreMap> cleanSentences = getCleanSentences(text);
						updateDomainInfo(directory.getName(), cleanSentences);						
					} catch (IOException e) {
						System.out.println("Errore nella lettura del file: " + f.getName());
						e.printStackTrace();
					}
			}
		}
	}
	
	//calcola il valore di tf-idf per il singolo termine(lemma)
	public double calculateTfIdf(String lemma, String domain){
		if(!this.lemmaFrequency.containsKey(lemma) || !this.lemmaFrequency.get(lemma).containsKey(domain))
			return 0;
		//numero di volte che il termine compare in quel dominio
		double n = this.lemmaFrequency.get(lemma).get(domain); 
		//lunghezza (n° termini) di quel dominio
		double d_length = this.domains.get(domain); 
		//numero di domini
		double ds = this.domains.size();  
		//numero di domini in cui compare il termine
		double dr = this.lemmaFrequency.get(lemma).size(); 	
		
		//componente tf
		double tf = n/d_length;
		//componente idf
		double idf = Math.log(ds/dr);
		
		return tf * idf;
	}
	
	//calcola il valore di tf-idf per tutti i termini
	public void calculateAll(){
		//itero su tutti i termini
		this.lemmaFrequency.entrySet().stream().forEach(entry -> {
			Map<String, Integer> domains = entry.getValue();
			String term = entry.getKey();
			//mappa contenente il valore tf-idf del termine per ogni dominio in cui compare
			Map<String, Double> newDomains = new TreeMap<>();
			//ottengo i valori tf-idf
			domains.keySet().forEach(domain -> {
				double tfIdf = calculateTfIdf(term, domain);
//				System.out.println(term + " " + tfIdf + " " + domain);
				newDomains.put(domain, tfIdf);
			});
			
			this.tf_idf.put(term, newDomains);
		});
	}
	
	public Map<String, Map<String, Double>> getTf_idf() {
		return tf_idf;
	}
	
	//metodo di supporto per la stampa dei valori
	public void printTfIdf(){
		List<String> out = new LinkedList<>();
		this.tf_idf.entrySet().forEach(entry -> {
			List<String> lines = new LinkedList<>();
			String line = entry.getKey() + ": [";
			entry.getValue().entrySet().forEach(d -> {
				lines.add(d.getKey() + "=" + d.getValue() + ", ");
			});
			for(String l : lines)
				line += l;
			line += "]\n";
			out.add(line);
		});
		
		String output = "";
		for(String s : out)
			output += s;
		
		System.out.println("Inizio stampa");
		Charset utf8 = StandardCharsets.UTF_8;
		try {
			Files.write(Paths.get("./dataset/tmp/tfidf.txt"), output.getBytes(utf8));
		} catch (IOException e) {
			System.out.println("errore stampa\n\n\n"+output);
			e.printStackTrace();
		}
	}
	
	public static void main(String[] args){
		Dictionary d = new Dictionary();
		d.prepareDictionary();
		d.calculateAll();
		d.printTfIdf();
	}
}
