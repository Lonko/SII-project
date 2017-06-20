package evaluators;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;

import tools.DBmanager;
import tools.Downloader;
import tools.TripleExtractor;
import tools.Wiki;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;

import de.tudarmstadt.ukp.wikipedia.parser.mediawiki.MediaWikiParser;
import de.tudarmstadt.ukp.wikipedia.parser.mediawiki.MediaWikiParserFactory;


public class TextAnalyzer {
	//Stringhe per l'accesso al db, da sostituire quando si cambia sistema
	public static final String DB_ADDRESS = "jdbc:mysql://localhost/wikisequencer";
	public static final String DB_USER = "root";
	public static final String DB_PASS = "passdb";
	//path per il file contenente il token per il TagMe
	private static final String TOKEN = "../gc-token.txt"; 
	//directory con i file relativi ai LOs
	private static final String DOC_DIR = "./dataset/documenti";
	//file contenente le dipendenze
	private static final String DEPENDENCIES_PATH = "./dataset/dipendenze.tsv";
	//soglia di tagme per accettare un'annotazione (in tagme valore rho nel json)
	private static final double SOGLIA_ANNOTAZIONE = 0.10;

	private String gc_token;
	private DBmanager db;
	private Wiki wiki; 	
	private MediaWikiParser parser;
	private Downloader downloader;
	//	private MaxentTagger posTagger;
	private TripleExtractor extractor;
	//mappa coppia di LOs -> tipo di relazione tra i due (1=esiste prerequisito, 2=non esiste prerequisito)
	private Map<String, String> dependencies = new HashMap<>();
	//mappa nome file -> annotazioni che rappresentano il LO
	private Map<String, HashSet<Integer>> name2Entities = new HashMap<>();
	//mappe nome file -> categorie e sottocategorie associate
	private Map<String, HashSet<String>> name2Categories = new HashMap<>();
	//mappa nome file -> testo
	private Map<String, String> name2Text = new HashMap<>();
	//mappa nome file ->testo taggato
	private Map<String, String> name2TaggedText = new HashMap<>();
	//mappa LO -> annotazioni
	private Map<String, HashSet<Integer>> name2Annotations = new HashMap<>();
	//mappa query -> risultati
	private Map<String, LinkedList<String>> query2Results = new HashMap<>();
	//mappa id -> titolo pagina annotazione
	private Map<Integer, String> id2Title = new HashMap<>();
	//mappa categorie associate alla pagina dell'annotazione -> id
	private Map<String, HashSet<Integer>> category2Ids = new HashMap<>();
	//mappa id -> categorie associate alla pagina dell'annotazione
	private Map<Integer, HashSet<String>> id2Categories = new HashMap<>();
	//mappa id -> testo prima sezione della pagina dell'annotazione
	private Map<Integer, String> id2TextDescription = new HashMap<>();
	//mappa id -> testo taggato della prima sezione della pagina dell'annotazione
	private Map<Integer, String> id2TaggedTextDescription = new HashMap<>();

	public TextAnalyzer(){
		try{
			byte[] encodedToken = Files.readAllBytes(Paths.get(TOKEN));
			this.gc_token = new String(encodedToken, StandardCharsets.UTF_8);
		} catch (IOException e) {
			e.printStackTrace();
		}
		this.db = new DBmanager(DB_ADDRESS,DB_USER,DB_PASS);
		//inizializzo wiki per le query a wikipedia
		this.wiki = new Wiki();
		this.wiki.setThrottle(10000);
		//istanza del parser
		MediaWikiParserFactory pf = new MediaWikiParserFactory();
		this.parser = pf.createParser();
		//istanza dell'estrattore di feature
		this.extractor = new TripleExtractor();
		//istanza del downloader
		this.downloader = new Downloader();
	}
	
	//legge le dipendenze di LOs
	private void readDependencies() throws IOException{
		BufferedReader br = new BufferedReader(new FileReader(DEPENDENCIES_PATH));
		try {
			String line = br.readLine();

			while (line != null) {
				String[] fields = line.trim().split("\t");
				System.out.println(fields[1]+"\t"+fields[2]);
				String key = fields[1].substring(0, fields[1].length()-4) + "\t" + fields[2].substring(0, fields[2].length()-4);
				this.dependencies.put(key, fields[0]);
				line = br.readLine();
			}
		} finally {
			br.close();
		}
	}

	/*legge tutti i file dei LOs, popolando la map name2text
	 si aspetta che DOC_DIR contenga delle directories ognuna relativa ad un dominio, contenente
	 i Learning Objects relativi a quel dominio */
	private void readFiles() {
		File[] directories = new File(DOC_DIR).listFiles(File::isDirectory);

		for(File directory : directories){
			File[] listOfFiles = directory.listFiles();

			for(File f : listOfFiles){
				try {
					byte[] encoded = Files.readAllBytes(Paths.get(f.toURI()));
					String text = new String(encoded, StandardCharsets.UTF_8);
					int l = f.getName().length();
					String name = directory.getName() + " * " + f.getName().substring(0, l-4);
					this.name2Text.put(name, text);
				} catch (IOException e) {
					System.out.println("Errore nella lettura del file: " + f.getName());
					e.printStackTrace();
				}
			}
		}
	}
	
	//legge cache su db
	private void readCache(){
		try{
			//legge le Categorie+Sottocategorie Wikipedia relative ai Learning Objects
			ResultSet rs1 = this.db.getTable("loCategories");
			while(rs1.next()){
				//leggo i campi
				String loname = rs1.getString("loname").replace("[codicetrattino]", "-")
						.replace("[codiceapostrofo]", "'");
				String categories = rs1.getString("categories");
				HashSet<String> catSet = new HashSet<>();
				for(String cat : categories.split("-")){
					String parsedCat = cat.replace("[codicetrattino]", "-")
							.replace("[codiceapostrofo]", "'");
					catSet.add(parsedCat);
				}
				//carico sulla mappa
				this.name2Categories.put(loname, catSet);
			}
			
			//legge le Categorie Wikipedia relative alle annotazioni trovate nei testi
			ResultSet rs2 = this.db.getTable("annotationCategories");
			while(rs2.next()){
				//leggo i campi
				int idAnnot = rs2.getInt("idAnnotation");
				String categories = rs2.getString("categories");
				HashSet<String> catSet = new HashSet<>();
				for(String cat : categories.split("-")){
					String parsedCat = cat.replace("[codicetrattino]", "-")
							.replace("[codiceapostrofo]", "'");
					catSet.add(parsedCat);
				}
				//carico sulle mappe
				this.id2Categories.put(idAnnot, catSet);
				for(String cat : catSet)
					if(this.category2Ids.containsKey(cat))
						this.category2Ids.get(cat).add(idAnnot);
					else{
						HashSet<Integer> ids = new HashSet<>();
						ids.add(idAnnot);
						this.category2Ids.put(cat, ids);
					}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		} 
		
		/* i file sono stati salvati in locale; si potrebbe pensare di salvarli su DB,
		   facendo attenzione ad eventuali caratteri speciali */
		File[] annDir = new File("./dataset/tmp/testiAnnotazioni").listFiles(File::isFile);
		File[] annTagDir = new File("./dataset/tmp/testiAnnotazioniTaggati").listFiles(File::isFile);

		//leggo i testi non taggati delle annotazioni
		for(File f : annDir){
			try {
				byte[] encoded = Files.readAllBytes(Paths.get(f.toURI()));
				String text = new String(encoded, StandardCharsets.UTF_8);
				int id = Integer.parseInt(f.getName());
				this.id2TextDescription.put(id, text);
			} catch (IOException e) {
				System.out.println("Errore nella lettura del file: " + f.getName());
				e.printStackTrace();
			}
		}
		
		//leggo i testi taggati delle annotazioni
		for(File f : annTagDir){
			try {
				byte[] encoded = Files.readAllBytes(Paths.get(f.toURI()));
				String text = new String(encoded, StandardCharsets.UTF_8);
				int id = Integer.parseInt(f.getName());
				this.id2TaggedTextDescription.put(id, text);
			} catch (IOException e) {
				System.out.println("Errore nella lettura del file: " + f.getName());
				e.printStackTrace();
			}
		}
	}

	//usa TagMe per trovare le annotazioni nel testo
	private JSONObject callTagme(String testo){
		JSONObject jObject=null;
		try{
			int finoA = testo.length()-1;

			HttpResponse<JsonNode> jsonResponse = Unirest.post("https://tagme.d4science.org/tagme/tag")

					.field("gcube-token", this.gc_token)
					.field("text", testo.substring(0,finoA))
					.field("lang", "en")
					.asJson();
			jObject = new JSONObject(jsonResponse.getBody().toString());

		}
		catch(Exception e){
			System.out.println(e.toString());
		}
		return jObject;
	}

	//restituisce le categorie [e sottocategorie] di una pagina wikipedia
	private HashSet<String> getCategories(String title, boolean subcategories){
		HashSet<String> categories = new HashSet<>();

		try {
			//prendo le categorie
			String [] cat = this.wiki.getCategories(title, true, true);
			Set<String> lcat = Arrays.asList(cat).stream()
					.map(s -> {
						int indexStart = s.indexOf(":");
						int indexEnd = s.indexOf("|");
						return s.substring(indexStart+1, indexEnd);
					}).collect(Collectors.toSet());
			categories.addAll(lcat);

			//prendo le sottocategorie se richiesto
			if(subcategories)
				for(String c : lcat){
					System.out.println(c);
					//14 è l'id che identifica il Category Member relativo alle sottocategorie
					String[] sub = this.wiki.getCategoryMembers(c, 14);
					Set<String> lsub = Arrays.asList(sub).stream()
							.map(s -> {
								int index = s.indexOf(":");
								return s.substring(index+1);
							}).collect(Collectors.toSet());
					categories.addAll(lsub);
				}
		} catch (IOException e) {
			System.out.println("Errore nella richiesta delle categorie");
			e.printStackTrace();
		}

		return categories;
	}

	//annota il testo sorgente dei LOs
	private void annotateLOs(){
		this.name2Text.forEach((k, v) ->{
			/*prendo le annotazioni relative al titolo
			  che dovrebbero rappresentare il LO */
			if(!this.name2Categories.containsKey(k)){
				/*prima dell'asterisco c'è il nome del dominio; interessano solo le annotazioni trovate
				  nella parte di stringa dopo l'asterisco */
				int index = k.indexOf("*");
				JSONObject annotatedTitle = callTagme(k+".");
				System.out.println(k);
				JSONArray titleAnnotations = (JSONArray)annotatedTitle.get("annotations");
				HashSet<String> categoriesLO = new HashSet<String>();

				for(int j = 0; j < titleAnnotations.length(); j++){
					JSONObject annotationTitle = (JSONObject) titleAnnotations.get(j);
					if(index < annotationTitle.getInt("start")){
						String title = annotationTitle.getString("title");
						categoriesLO.addAll(getCategories(title, true));
					}
				}
				this.name2Categories.put(k, categoriesLO);
				//salvo su db
				String values4LO = "'"+k.replace("-", "[codicetrattino]")
						.replace("'", "[codiceapostrofo")+"','";
				for(String cat : categoriesLO){
					String value = cat.replace("-", "[codicetrattino]").replace("'", "[codiceapostrofo]");
					values4LO += value+"-";
				}
				values4LO += "'";
				db.insert("loCategories", "loname,categories", values4LO);
			}

			/*prendo le annotazioni del testo del LO 
			 * le memorizzo e sostituisco gli spot nel testo originale con l'id */
			JSONObject annotatedLO = callTagme(v);
			JSONArray annotations = (JSONArray) annotatedLO.get("annotations");
			HashSet<Integer> annots = new HashSet<Integer>();
			StringBuilder builder = new StringBuilder("");
			int minIndex = 0, maxIndex = 0;

			for(int i = 0; i < annotations.length(); i++){
				JSONObject annotation = (JSONObject) annotations.get(i);
				if(annotation.getDouble("rho") >= SOGLIA_ANNOTAZIONE){
					int idAnnot = annotation.getInt("id");
					String title = annotation.getString("title");
					String spot = annotation.getString("spot");
					maxIndex = (Integer) annotation.get("start");
					annots.add(idAnnot);
					this.id2Title.put(idAnnot, title);
					/*in alcuni casi ci sono annotazioni relativi a spot parzialmente sovrapposti;
				 	  potrebbe essere utile rivedere come gestire questi casi;
				 	  al momento vengono semplicemente inseriti gli id+spot uno dopo l'altro */
					try{
						builder.append(v.substring(minIndex, maxIndex));
						builder.append("[[["+String.valueOf(idAnnot)+"|"+spot+"]]]");
					} catch (Exception e) {
						builder.append(" "+"[[["+String.valueOf(idAnnot)+"|"+spot+"]]]");
					}
					minIndex = (Integer) annotation.get("end");
					
					//controllo se ho già preso le categorie per questa annotazione
					if(!this.id2Categories.containsKey(idAnnot)){
						//prendo le categorie relative a questa annotazione se non presenti nella mappa
						HashSet<String> categories = getCategories(title, false);
						for(String category : categories){
							if(!this.category2Ids.containsKey(category)){
								HashSet<Integer> ids = new HashSet<Integer>();
								ids.add(idAnnot);
								this.category2Ids.put(category, ids);
							}else{
								this.category2Ids.get(category).add(idAnnot);
							}	
						}
						this.id2Categories.put(idAnnot, categories);
						//salvo su db
						String values4Annot = "'"+idAnnot+"','";
						for(String cat : categories){
							String value = cat.replace("-", "[codicetrattino]").replace("'", "[codiceapostrofo]");
							values4Annot += value+"-";
						}
						values4Annot += "'";
						db.insert("annotationCategories", "idAnnotation,categories", values4Annot);
					}
					
				}
			}
			this.name2TaggedText.put(k, builder.toString());
			this.name2Annotations.put(k, annots);
		});
	}

	/*prende le descrizioni (ovvero il primo paragrafo)
	 delle pagine wikipedia relative alle annotazioni*/
	private void getDescriptions(){
		this.id2Title.forEach((k,v)->{
			if(!this.id2TextDescription.containsKey(k))
				try {
					String text = wiki.getRenderedText( Jsoup.parse(v).text());
					String desc = parser.parse(text).getText().split("\nContents\n")[0];
					String cleanDesc = desc.replaceAll("\\n+", "").trim();
					this.id2TextDescription.put(k, cleanDesc);
					//taggo il testo della descrizione della pagina Wikipedia
					tagText(k, cleanDesc, "");
					printStuff(cleanDesc, "testiAnnotazioni/"+k);
				} catch (Exception e) {
					e.printStackTrace();
					System.out.println("Errore nella lettura della pagina: " + v);
				}
		});
	}


	//annota il testo delle pagine di Wikipedia e dei risultati delle query
	private void tagText(int id, String text, String query){
		//Tagme va in timeout su testi troppo lunghi, quindi divido in gruppi di massimo 2000 caratteri
		String taggedText = "";
		int start = 0, end;
		if(text.length() > 2000)
			end = 2000;
		else
			end = text.length();
		
		do{
			String subText = text.substring(start, end); 
			if(subText.length() == 0){
				start = end;
				end += 2000;
				continue;
			}
			JSONObject annotatedDesc = callTagme(subText);
			JSONArray annotations;
			annotations = (JSONArray) annotatedDesc.get("annotations");
			int minIndex = 0, maxIndex = 0;
			StringBuilder builder = new StringBuilder("");

			for(int i = 0; i < annotations.length(); i++){
				JSONObject annotation = (JSONObject) annotations.get(i);
				if(annotation.getDouble("rho") >= SOGLIA_ANNOTAZIONE){
					try{
						int idAnnot = annotation.getInt("id");
						String title = annotation.getString("title");
						String spot = annotation.getString("spot");
						maxIndex = (Integer) annotation.get("start");
						try{
							builder.append(subText.substring(minIndex, maxIndex));
							//L'id e lo spot sono racchiusi tra triple parentesi quadre
							builder.append("[[["+String.valueOf(idAnnot)+"|"+spot+"]]]");
						} catch (Exception e) {
							builder.append(" "+"[[["+String.valueOf(idAnnot)+"|"+spot+"]]]");
						}
						minIndex = (Integer) annotation.get("end");

						//controllo se ho già preso le categorie per questa annotazione
						if(!this.id2Categories.containsKey(idAnnot)){
							//prendo le categorie relative a questa annotazione se non presenti nella mappa
							HashSet<String> categories = getCategories(title, false);
							for(String category : getCategories(title, false)){
								if(!this.category2Ids.containsKey(category)){
									HashSet<Integer> ids = new HashSet<Integer>();
									ids.add(idAnnot);
									this.category2Ids.put(category, ids);
								}else{
									this.category2Ids.get(category).add(idAnnot);
								}
							}
							this.id2Categories.put(idAnnot, categories);		
							//salvo su db
							String values4Annot = "'"+idAnnot+"','";
							for(String cat : categories){
								String value = cat.replace("-", "[codicetrattino]").replace("'", "[codiceapostrofo]");
								values4Annot += value+"-";
							}
							values4Annot += "'";
							db.insert("annotationCategories", "idAnnotation,categories", values4Annot);
						}
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
			start = end;
			end += 2000;
			taggedText += builder.toString();
		}while(end <= text.length());

		//id = -100 è relativo ai risultati delle query
		if(id != -100){
			this.id2TaggedTextDescription.put(id, taggedText);
			printStuff(taggedText, "testiAnnotazioniTaggati/"+id);
		}else{
			if(!this.query2Results.containsKey(query)){
				LinkedList<String> results = new LinkedList<String>();
				results.add(taggedText);
				this.query2Results.put(query, results);
			}
			else
				this.query2Results.get(query).add(taggedText);
		}

	}
	
	/*prende pagine web in cui compaiono coppie di LO per incrementare il dataset
	 * NB: Google permette un massimo di 100 query giornaliere!!
	 */
	private void getWebData(){
		//lista dei los su cui iterare
		Set<String> los = this.name2Text.keySet();

		//confronto dei los 
		for(String lo1 : los)
			for(String lo2 : los){
				//effettuo il confronto solo per le coppie a cui sono interessato
				int index1 = lo1.indexOf("*")+2;
				int index2 = lo2.indexOf("*")+2;
				if(!this.dependencies.containsKey(lo1.substring(index1)+"\t"+lo2.substring(index2)))
					continue;
				
				//costruzione della query
				String[] lo1Split = lo1.split("\\*");
				String[] lo2Split = lo2.split("\\*");
				String query = (lo1Split[0].trim() + " " + lo1Split[1].trim() + " " + lo2Split[1].trim())
						.replaceAll("\\-|\\w*\\d+\\w*\\s*", "");
				
				this.downloader.fetchWebData(query).stream().forEach(result -> tagText(-100, result, query));
			}
	}

	//per ogni LO cerco gli id che lo rappresentano in base alle categorie di Wikipedia
	private void matchLOs2Ids(){		
		Map<String, HashSet<Integer>> lo2Ids = new HashMap<>();
		
		this.name2Categories.entrySet().stream().forEach(entry -> {
			HashSet<Integer> ids = new HashSet<>();
			for(String cat : entry.getValue()){
				if(this.category2Ids.containsKey(cat))
					ids.addAll(this.category2Ids.get(cat));
			}
			lo2Ids.put(entry.getKey(), ids);
		});
		
		this.name2Entities.putAll(lo2Ids);
	}
	
	//dal testo estrae solo le frasi in cui compaiono sia il LO1 che il LO2
	private List<String> getRelevantSentences(String taggedText, HashSet<Integer> lo1, HashSet<Integer> lo2){
		List<String> sentences = Arrays.asList(taggedText.split("\\."));

		return sentences.stream().filter(s -> {
			boolean hasLO1 = false, hasLO2 = false;
			for(int id : lo1){
				hasLO1 = s.contains(String.valueOf(id));
				if(hasLO1) break;
			}
			for(int id : lo2){
				hasLO2 = s.contains(String.valueOf(id));
				if(hasLO2) break;
			}
			return (hasLO1 && hasLO2);
		}).collect(Collectors.toList());
	}

	//restituisce le triple su cui estrarre le feature levando i tag
	private String getTriples(List<String> texts, HashSet<Integer> lo1, HashSet<Integer> lo2, String classification){
		List<String> newSentences = new ArrayList<>();
		HashSet<String> lo1Identities = new HashSet<>();
		HashSet<String> lo2Identities = new HashSet<>();

		//prepara l'input per il feature extractor
		for(String text : texts){
			if(text.length() <= 1)
				continue;
			List<String> sentences = getRelevantSentences(text, lo1, lo2);
			//pattern che matcha i tag
			Pattern p = Pattern.compile("\\[\\[\\[(.*?)\\]\\]\\]"); 

			//sostituisco i tag memorizzando le identità associate ai LOs
			sentences.stream().forEach(sentence -> {
				String s = sentence;
				Matcher m = p.matcher(sentence);
				while(m.find()){
					String[] tag = m.group().split("\\|");
					tag[0] = tag[0].replace("[", "");
					tag[1] = tag[1].replace("]", "");
					s = s.replace("[[["+tag[0]+"|"+tag[1]+"]]]", tag[1]);
					if(lo1.contains(Integer.parseInt(tag[0])))
						lo1Identities.add(tag[1]);
					if(lo2.contains(Integer.parseInt(tag[0])))
						lo2Identities.add(tag[1]);
				}
				s = s.replace("\n", "").trim();
				if(!s.equals(""))   
						newSentences.add(s);
			});
		}
		
		return this.extractor.getRelevantTriples(lo1Identities, lo2Identities, newSentences, classification);
	}

	//metodo per la stampa delle triple estratte per tutte le coppie di LOs in input
	private void textAnalysis(){
		//stringa da stampare sul file da dare in input a Weka
		String output = "";
		//set los su cui iterare
		Set<String> los = this.name2Text.keySet();
		
		//confronto dei los 
		for(String lo1 : los)
			for(String lo2 : los){

				//effettuo il confronto solo per le coppie a cui sono interessato
				int index1 = lo1.indexOf("*")+2;
				int index2 = lo2.indexOf("*")+2;
				if(!this.dependencies.containsKey(lo1.substring(index1)+"\t"+lo2.substring(index2)))
					continue;
			
				String classification = this.dependencies.get(lo1.substring(index1)+"\t"+lo2.substring(index2));
				
				//stringa contenente le triple
				String singleOutput = ""; 
				
				System.out.println(lo1.substring(index1)+"\t"+lo2.substring(index2));
				
				//costruzione della query e del nome del file
				String[] lo1Split = lo1.split("\\*");
				String[] lo2Split = lo2.split("\\*");
				String query = (lo1Split[0].trim() + " " + lo1Split[1].trim() + " " + lo2Split[1].trim())
						.replaceAll("\\-|\\w*\\d+\\w*\\s*", "");
				
				
				//prendo gli Id corrispondenti ai los
				HashSet<Integer> lo1Entities = this.name2Entities.get(lo1);
				HashSet<Integer> lo2Entities = this.name2Entities.get(lo2);
				//prendo i testi dei los
				String lo1Text = this.name2TaggedText.get(lo1);
				String lo2Text = this.name2TaggedText.get(lo2);

				LinkedList<String> input = new LinkedList<String>();

				//prendo le triple relative al testo di lo1
				input.add(lo1Text);
				singleOutput += getTriples(input, lo1Entities, lo2Entities, classification);

				//prendo le triple relative al testo di lo2
				input.clear();
				input.add(lo2Text);
				singleOutput += getTriples(input, lo1Entities, lo2Entities, classification);

				//prendo i testi delle annotazioni di lo1 e ne estraggo le triple
				input.clear();
				HashSet<Integer> annotations1 = this.name2Annotations.get(lo1);
				for(int id : annotations1)
					input.add(this.id2TaggedTextDescription.get(id));
				singleOutput += getTriples(input, lo1Entities, lo2Entities, classification);

				//prendo i testi delle annotazioni di lo2 e ne estraggo le triple
				input.clear();
				HashSet<Integer> annotations2 = this.name2Annotations.get(lo2);
				for(int id : annotations2)
					input.add(this.id2TaggedTextDescription.get(id));
				singleOutput += getTriples(input, lo1Entities, lo2Entities, classification);
				
				/* estaggo le triple per i risultati di una query sottoposta al Search Engine
				 * la query è nella forma {dominio+ lo1 + lo2} */
				input.clear();
				//solo se la query ha restituito dei risultati
				if(this.query2Results.containsKey(query))
					input = this.query2Results.get(query);
				singleOutput += getTriples(input, lo1Entities, lo2Entities, classification);
				
				if(singleOutput.equals(""))
					printStuff(classification, "newResults/" + query + ".tsv");
				else
					printStuff(singleOutput, "newResults/" + query + ".tsv");
				output += singleOutput;
			}
		System.out.println("Fine valutazione.\t\t" + !output.equals(""));
		printStuff(output, "newResults/fullOutput.tsv");
	}

	private void initialize(){
		//leggo le dipendenze
		try {
			readDependencies();
		} catch (IOException e) {
			e.printStackTrace();
		}
		//leggo i LOs
		readFiles();
		this.db.openConnection();
		//leggo la cache
		readCache();
		//prendo annotazioni LO
		annotateLOs();
		//prendo le descrizioni dalle pagine di wikipedia delle annotazioni
		getDescriptions();
		//eseguo le query e ne prendo i risultati
		getWebData();
		this.db.closeConnection();
		//associo gli Id ai LOs
		matchLOs2Ids();
	}

	//semplice metodo di supporto per la stampa 
	private void printStuff(String output, String name){
		System.out.println("Inizio stampa");
		Charset utf8 = StandardCharsets.UTF_8;
//		File dir = new File("./dataset/tmp/"+name);
		try {
			Files.write(Paths.get("./dataset/tmp/"+name), output.getBytes(utf8));
		} catch (IOException e) {
			System.out.println("errore stampa triple\n\n\n"+output);
			e.printStackTrace();
		}
	}

	public static void main(String[] args) {
		TextAnalyzer evaluator = new TextAnalyzer();
		System.out.println("Inizializzazione");		
		evaluator.initialize();
		System.out.println("Fine Inizializzazione \n\n Inizio Valutazione");
		evaluator.textAnalysis();

	}



}
