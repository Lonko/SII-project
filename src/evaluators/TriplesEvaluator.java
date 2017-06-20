package evaluators;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TriplesEvaluator {
	//directory con i file contenenti le triple
	private static final String TRIPLE_PATH = "./dataset/tmp/newResults";
	
	//mappa dominio + coppia di LOs -> triple
	private Map<String, List<String>> triples = new HashMap<>();
	//mappa dominio + coppia di LOs -> classe  (per le coppie di LOs oer le quali non sono state estratte triple)
	private Map<String, String> couplesClass = new HashMap<>();
	//mappa relazione -> indice
	private Map<String, Integer> relationsIndexes = new HashMap<>();
	
	public TriplesEvaluator(){
		
	}
	
	private Map<String, List<String>> readTriples() throws IOException{
		//mappa <coppia di LOs> -> triple
		Map<String, List<String>> triplesRead = new HashMap<>();
		
		//itero su tutte le cartelle
		File[] domains = new File(TRIPLE_PATH).listFiles(File::isDirectory);
		for(File d : domains){
			//itero su tutti i file della cartella
			File[] triplesFiles = d.listFiles(File::isFile);
			for(File f : triplesFiles){
				//il nome del file è in formato <dominio>-<nome lo1>-<nome lo2>
				/* i nomi non corrispondono esattamente al nome del file dei sottotitoli originali
				 * ma alla stringa usata nella query sottoposta a google nella fase di estrazione delle
				 * triple */
				String names = f.getName().split("\\.")[0].replace("-","\t");

				//leggo le triple contenute nel file
				List<String> triples = new ArrayList<>();
				BufferedReader br = new BufferedReader(new FileReader(f.getPath()));
				try {
					String line = br.readLine();
					boolean hasTriples = true;
					
					//se il file contiene solo il carattere "1" o "2" allora nessuna tripla è stata estratta
					if(line != null && (line.equals("1") || line.equals("2"))){
						this.couplesClass.put(names, line);
						hasTriples = false;
					}

					while (line != null && hasTriples) {
						String[] splitLine = line.trim().split("\t");
						this.couplesClass.put(names, splitLine[0]);
						//rimuovo stopword dalla relazione
						String relation = splitLine[2].replaceAll(" for | for$", " ").replaceAll(" in | in$", " ")
								.replaceAll(" of | of$", " ").replaceAll(" to | to$", " ")
								.replace("shortly", "").replace("often", "").replaceAll(" +", " ").trim();
						triples.add(splitLine[0]+"\t"+splitLine[1]+"\t"+relation+"\t"+splitLine[3]);
						line = br.readLine();
					}
				} finally {
					br.close();
				}
				//aggiungo le triple alla mappa
				triplesRead.put(names, triples);
			}
		}
		
		return triplesRead;
	}
	
	/* conta il numero di volte che ogni soggetto, relazione ed oggetto compaiono
	 * ritorna una lista di mappe con gli elementi da escludere	 */
	private List<Map<String, Integer>> countElements(){
		Map<String, Integer> subjectCount = new HashMap<>();
		Map<String, Integer> relationCount = new HashMap<>();
		Map<String, Integer> objectCount = new HashMap<>();
		
		this.triples.entrySet().forEach(entry -> {
			entry.getValue().forEach(triple -> {
				String[] elements = triple.split("\t");
				if(subjectCount.containsKey(elements[1])){
					int newValue = subjectCount.get(elements[1]) +1;
					subjectCount.put(elements[1], newValue);
				} else
					subjectCount.put(elements[1], 1);
				if(relationCount.containsKey(elements[2])){
					int newValue = relationCount.get(elements[2]) +1;
					relationCount.put(elements[2], newValue);
				} else
					relationCount.put(elements[2], 1);
				if(objectCount.containsKey(elements[3])){
					int newValue = objectCount.get(elements[3]) +1;
					objectCount.put(elements[3], newValue);
				} else
					objectCount.put(elements[3], 1);
			});
		});
		
		//salvo gli indici delle relazioni più frequenti da utilizzare nella stampa delle features
		//durante la valutaziones
		ArrayList<String> relations = relationCount.entrySet().stream()
				.filter(entry -> entry.getValue() > 2)
				.filter(entry -> entry.getKey().split(" ").length <= 3 && entry.getKey().length() < 25 && !entry.getKey().equals("="))
				.sorted((e1, e2) -> e2.getValue() - e1.getValue())
				.map(entry -> entry.getKey()).collect(Collectors.toCollection(ArrayList::new));
	
		
		for(int i = 0; i < relations.size(); i++)
			this.relationsIndexes.put(relations.get(i), i+1);
		
		//ritorno le liste contenenti gli elementi da filtrare
		List<Map<String, Integer>> elementsCount = new ArrayList<>();
		elementsCount.add(subjectCount.entrySet().stream().filter(entry -> entry.getValue() <= 2)
				.collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue())));
		elementsCount.add(relationCount.entrySet().stream().filter(entry -> entry.getValue() <= 2 || 
					!(entry.getKey().split(" ").length <= 3) || !(entry.getKey().length() < 25) || entry.getKey().equals("="))
				.collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue())));
		elementsCount.add(objectCount.entrySet().stream().filter(entry -> entry.getValue() <= 2)
				.collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue())));
		
		return elementsCount;
	}
	
	/*filtra le triple che hanno un soggetto, relazione od oggetto poco frequenti
	/ (o relazioni troppo lunghe)*/
	private void filterTriples(){
		List<Map<String, Integer>> elementsCount = countElements();
		Map<String, List<String>> newTriples = new HashMap<>();

		this.triples.entrySet().forEach(entry -> {
			List<String> triplesList = entry.getValue().stream().filter(triple -> {
				String[] elements = triple.split("\t");
				boolean isSignificant = true;
				if(elementsCount.get(0).containsKey(elements[1]) || elementsCount.get(1).containsKey(elements[2]) 
						|| elementsCount.get(2).containsKey(elements[3]))
					isSignificant = false;
				return isSignificant;
			}).collect(Collectors.toList());
			
			newTriples.put(entry.getKey(), triplesList);
		});
		
		this.triples = newTriples;
	}
	
	//inizializzazione per la valutazione delle triple
	private void initialize(){
		try {
			this.triples = readTriples();
		} catch (IOException e) {
			e.printStackTrace();
		}
		filterTriples();
	}
	
	public String evaluation(){
		StringBuilder wekaInputBuilder = new StringBuilder("");
		
		this.triples.entrySet().forEach(entry -> {
			List<String> triples = entry.getValue();
			//vettore delle features
			int[] features = new int[this.relationsIndexes.size()+2];
			
			//la prima feature è il numero totale di triple (escluse le filtrate) estratte per la coppia di LOs
			features[0] = triples.size();
			boolean classified = false;
			
			if(triples.isEmpty())
				//coppie di LOs per le quali non ho estratto dati vengono ignorate
				features[this.relationsIndexes.size()+1] = Integer.parseInt(this.couplesClass.get(entry.getKey()));
			else
				for(String t : triples){
					String[] splitTriple = t.split("\t");
					if(!classified){
						//l'ultima feature è la classe della coppia di LOs
						features[this.relationsIndexes.size()+1] = Integer.parseInt(this.couplesClass.get(entry.getKey()));
						classified = true;
					}
					//aumento il contatore per la relazione di questa tripla
					int index = this.relationsIndexes.get(splitTriple[2]);
					features[index] += 1;
				}
			
			//aggiungo il record per questa coppia di LOs
			String line = Arrays.stream(features)
			        .mapToObj(String::valueOf)
			        .collect(Collectors.joining(","));
			wekaInputBuilder.append("\n"+line);
		});
		
		return wekaInputBuilder.toString(); 
	}
	
	//stampa il file di input per weka
	public void printArff(String data) throws FileNotFoundException{
		PrintWriter weka = new PrintWriter("./dataset/filesClassificazione/triplesRelations.arff");
		
		String[] relFeatures = new String[this.relationsIndexes.size()];
		
		//costruzione della parte di template relativa alle feautures
		this.relationsIndexes.entrySet().forEach(entry -> {
			int index = entry.getValue()-1;
			String relation = entry.getKey();
			relFeatures[index] = "@ATTRIBUTE " + relation.replace("'", "").replace(" ", "_") + " NUMERIC";
		});
		
		String joinedFeatures = String.join("\n", relFeatures);
		
		//stampa file arff
		weka.print("% 1. Title: Learning Objects Triples For Knowledge Prerequisite Indentification\n"+
				"% \n"+
				"% 2. Sources:\n"+
				"%      (a) Creator:Marco Monteleone\n"+
				"%      (b) Date: June 2017\n"+
				"% 3. Classes:\n"+
				"% (a) Class 1: lo1 is prerequisite of lo2\n"+
				"% (b) Class 2: lo1 is not prerequisite of lo2"+
				"%\n"+
				"@RELATION losTriples\n"+
				//numero totale di triple estratte (escluse le filtrate) per coppia di LOs
				"@ATTRIBUTE totalTriples NUMERIC\n"+
				//numero di volte che ogni relazione è stata trovata per la coppia di LOs
				joinedFeatures+"\n"+
				//classe della coppia di LOs
				"@ATTRIBUTE expected_class {1,2}\n"+
				"\n"+
				"@Data"+data);
				
		
		weka.close();
	}
	
	public static void main(String[] args){
		TriplesEvaluator te = new TriplesEvaluator();
		te.initialize();
		String data = te.evaluation();
		try {
			te.printArff(data);
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
