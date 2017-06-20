package evaluators;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.ResultSet;
import java.util.*;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;

import de.tudarmstadt.ukp.wikipedia.parser.Link;
import de.tudarmstadt.ukp.wikipedia.parser.ParsedPage;
import de.tudarmstadt.ukp.wikipedia.parser.Section;
import de.tudarmstadt.ukp.wikipedia.parser.mediawiki.MediaWikiParser;
import de.tudarmstadt.ukp.wikipedia.parser.mediawiki.MediaWikiParserFactory;
import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.TaggedWord;
import edu.stanford.nlp.tagger.maxent.MaxentTagger;

import org.jsoup.Jsoup;

import tools.DBmanager;
import tools.Wiki;

public class Sequencer {
	
	//costanti
	
	//attiva messaggi debug
		public static final boolean debug = true;
	
	//directory contenente i file da analizzare
	public static final String DOC_DIR = "./documenti2";
	public static final String EXPECTED_PATH = "./dipendenze/expected.csv";
	public static final String NOME_FILE_WEKA = "DatiCalssificazioneWeka.arff";
	public static final String NOME_FILE_R = "DatiCalssificazioneR.csv";
	//Stringhe per l'accesso al db, da sostituire quando si cambia sistema
	public static final String DB_ADDRESS = "jdbc:mysql://localhost/wikisequencer";
	public static final String DB_USER = "root";
	public static final String DB_PASS = "mysql";
	
	//soglia di tagme per accettare un'annotazione (in tagme valore rho nel json)
	public static final double SOGLIA_ANNOTAZIONE = 0.15;
	
	
	
	
	//variabili
	//nome file to annotazini
	Map<String, Set<Integer>> annotations = new HashMap<String, Set<Integer>>();
	//nome file to nouns
	Map<String, Set<String>> fileName2Nouns = new HashMap<String, Set<String>>();
	//id wikipedia to title
	Map<Integer, String> wikiId2Title = new HashMap<Integer, String>();
	//wikipedia title to id
	Map<String, Integer> title2WikiId = new HashMap<String, Integer>();
	//id to testo wikipedia...in sospeso per problema caratteri speciali a db
	Map<Integer, String> wikiId2Text = new HashMap<Integer, String>();
	//id wikipedia to lunghezza testo pagina
	Map<Integer, Integer> wikiId2Length = new HashMap<Integer, Integer>();
	//id wikipedia to lunghezza prima sezione pagina
	Map<Integer, Integer> wikiId21stSectLength = new HashMap<Integer, Integer>();
	//id wikipedia to titoli pagina dei link
	Map<Integer, Set<String>> wikiId2AllLinksText = new HashMap<Integer, Set<String>>();
	//id wikipedia to testo presente nei link
	Map<Integer, Set<String>> wikiId2AllLinksTextTrue = new HashMap<Integer, Set<String>>();
	//id to id link pagine....non pi� utilizzato faceva troppe richieste a wikipedia bloccando il sistema
	Map<Integer, Set<Integer>> wikiId2AllLinks = new HashMap<Integer, Set<Integer>>();
	//id to id link pagine prima sezione....non pi� utilizzato faceva troppe richieste a wikipedia bloccando il sistema
	Map<Integer, Set<Integer>> wikiId21stSectLinks = new HashMap<Integer, Set<Integer>>();
	//id wikipedia to testo presente nei link prima sezione
	Map<Integer, Set<String>> wikiId21stSectLinksText = new HashMap<Integer, Set<String>>();
	//id wikipedia to titoli pagina dei link prima sezione
	Map<Integer,Set<String>> wikiId2LinksTitle = new HashMap<Integer, Set<String>>();
	
	Wiki wiki; 
	DBmanager db;
	MediaWikiParser parser;
	MaxentTagger posTagger;
	
	public Sequencer(){
		
		
		
		//inizializzo l'oggetto per l'accesso al db
		db = new DBmanager(DB_ADDRESS,DB_USER,DB_PASS);
		
		//inizializzo wiki per le query a wikipedia
		wiki = new Wiki();
		wiki.setThrottle(10000);
		
		//istanza del parser
		MediaWikiParserFactory pf = new MediaWikiParserFactory();
		parser = pf.createParser();
		
		//istanza posTagger
		posTagger = new MaxentTagger("./models/english-bidirectional-distsim.tagger");
	}
	
	
	//chiamata api tagme prende in input una stringa che contiene
	//il testo di un LO e ritorna il JSONObject con le annotazioni
	
	public JSONObject callTagme(String testo){
		JSONObject jObject=null;
		try{
			//di pu� decidere il timeout per le richieste, tagme su lunghi testi va in timeout facilmente
			//si potrebbe pensare di annotare solo una parte iniziale del testo, almeno quando il
			//testo � preso da una pagina wikipedia
			//Unirest.setTimeouts(50000, 50000);
			int finoA =200;
			if(testo.length()<200)
				finoA = testo.length()-1;
			
			//per riannotare tutto levare commento
			//finoA = testo.length()-1;
			
			HttpResponse<JsonNode> jsonResponse = Unirest.post("https://tagme.d4science.org/tagme/tag")

					.field("gcube-token", "125d6a78-fa1b-4a0c-89e5-cc488487d134")
					.field("text", testo.substring(0,finoA))
					.field("lang", "en")
					.asJson();
       
			jObject = new JSONObject(jsonResponse.getBody().toString());
			
		}
		catch(Exception e){
			System.out.println("errore durante chiamata alle api TagMe");
			System.out.println(e.toString());
		}
		return jObject;
	}
	
	//prende in input un LO, ne estrae il testo e
	// fa la chiamata a Tagme tramite la funzione callTagme
	// riempiendo la LIST di id a pagine wikipedia
	
	
	
	
	
	
	public Set<Integer> getAnnotation(String lo) throws IOException{
		
		Set<Integer> ret = new HashSet<Integer>();
		String text="";
		
		
		//leggo testo file
		if(debug)
			System.out.println("leggo testo file "+lo);
		try{
			text = readFile(lo);
		}
		catch(Exception e){
			System.out.println("errore durante lettura del file");
			System.out.println(e.toString());
		}
		if(debug)
			System.out.println("finito di leggere inizio chiamata a tagme");
		
		//chiamo le api tagme tramite il metodo implementato nella classe calltagme
		JSONObject tagme = callTagme(text);
		System.out.println(tagme.toString());
		if(debug)
			System.out.println("finita chiamata a tagme");
		
		//itero sulle varie annotazioni ed estraggo quello che mi serve

		JSONArray arr = (JSONArray) tagme.get("annotations");

		if(debug)
			System.out.println("ho trovato le seguenti annotazioni che superano la soglia di accetabilit�");
		for (int i = 0; i < arr.length(); i++) {
			JSONObject jsonObj = (JSONObject) arr.get(i);
			if(jsonObj.getDouble("rho") > SOGLIA_ANNOTAZIONE){
				ret.add(jsonObj.getInt("id"));

				wikiId2Title.put(jsonObj.getInt("id"), jsonObj.getString("title"));
				title2WikiId.put(jsonObj.getString("title"),jsonObj.getInt("id"));
				if(debug)
					System.out.println("["+jsonObj.getString("spot")+"] con probabilit�: "+ jsonObj.getDouble("rho")+ " ---- Titolo pagina wiki: "+ jsonObj.getString("title")+" ---- Id wiki: "+jsonObj.getInt("id")+"\n");

			}

		}
		//inserisco nella mappa delle annotazioni
		annotations.put(lo,ret);
		return ret;
	}
	
	
	
	//annota un Lo prendendo le annotazioni da getAnnotation e
	//attraverso Wiki prende le pagine wikipedia e salva i dati
	//utilizzati per le analisi del classificatore
	
	public void annotate(String lo) throws Exception{
		
		String text = "";
		String title="";
		String parsedText;
		int len=0;
		int firstlen=0;
		Set<Integer> outLinks;	
		Set<String> outLinksText;
		Set<Integer> outFirstLinks;	
		Set<String> outFirstLinksText;
		Set<String> linksTitles;
		Set<String> linksAllTitles;
		
		String values4Db ="";
		
		//prendo la lista di annotazioni relative ad un lo
		Set<Integer> Los = getAnnotation(lo);
		Set<Integer> copyLos=new HashSet<Integer>();
		copyLos.addAll(Los);
		System.out.println(copyLos);
		
		//tolgo dalla lista gli id che sono presenti nell'array wikiId2Length
		//e che quindi sono stati gi� annotati in modo da velocizzare il processo
		//per lo che hanno annotazioni in comune e non ripetere le chiamate a Wikipedia
		//collo di bottiglia per le prestazoni dell'annotatore

		if(Los.removeAll(wikiId2Length.keySet())){
			System.out.println("elemento in cache salto");
		}
			
		//Los non validi (che generano errori nella cattura del testo)
		Set<Integer> wrongLos = new HashSet<Integer>();
		
		//itero sulle annotazioni
		for(Integer id : Los){
			values4Db="";
			//prendo il titolo ed elimino caratteri speciali
			title=wikiId2Title.get(id);
			title = title.replace("&quot;", "\"");
			
			//prendo il testo della pagina a partire dal titolo
			try{
				text=wiki.getPageText( Jsoup.parse(title).text());
			}catch(Exception e){
				System.out.println("Ho saltato " + title);
				wrongLos.add(id);
				continue;
			}
			
			
			//parser per la pagina dal testo
			ParsedPage pp = parser.parse(text);
			
			parsedText=pp.getText();
			len=pp.getText().length();
			//estrazione dei valori per il classificatore a partire dalla pagina
			wikiId2Text.put(id, parsedText);
			wikiId2Length.put(id, len);
			values4Db+="'"+id+"','"+len;
			
			List<Section> sezioni = pp.getSections();
			
			for(Section s : sezioni){
				if(s.getTitle()==null){
					firstlen=s.getText().length();
					break;
				}
					
			}
			wikiId21stSectLength.put(id,firstlen);
			values4Db+="','"+firstlen+"','";
			
			linksAllTitles = new TreeSet<String>();
			outLinksText = new TreeSet<String>();		
			outLinks = new TreeSet<Integer>();
			for (Link link : pp.getLinks()) {
				if (Link.type.INTERNAL == link.getType()) {
					int idLink = -1;             //????
					String app="";
					app=link.getTarget().replace("-", "[codiceatrattino]");
					
					linksAllTitles.add(link.getTarget());
					values4Db+=app.replace("'", "[codiceapostrofo]")+"-";
					outLinksText.add(link.getText());
					if (idLink >= 0){
						
						outLinks.add(new Integer(idLink));
						
					}
						
				}
			}
			values4Db+="','";
			
			for(String s : outLinksText){
				String app;
				app=s.replace("-", "[codiceatrattino]");
				values4Db+=app.replace("'", "[codiceapostrofo]")+"-";
			}
			values4Db+="','";
			
			wikiId2AllLinks.put(id, outLinks);
			wikiId2AllLinksText.put(id, linksAllTitles);
			wikiId2AllLinksTextTrue.put(id, outLinksText);
			
			
			
			
			
			outFirstLinksText = new TreeSet<String>();
			linksTitles = new TreeSet<String>();
			outFirstLinks = new TreeSet<Integer>();
			for (Section section : pp.getSections()) {
				if (section.getTitle() == null) {
					for (Link link : section.getLinks(Link.type.INTERNAL)) {
						int idLink = -1; //getId(link.getTarget());
						
						String app="";
						app=link.getTarget().replace("-", "[codiceatrattino]");
						
						linksTitles.add(link.getTarget());
						values4Db+=app.replace("'", "[codiceapostrofo]")+"-";
						if (idLink >= 0){
							outFirstLinks.add(new Integer(idLink));
							outFirstLinksText.add(link.getText());
							
						}
							
					}
					
					break;
				}
			}
			wikiId2LinksTitle.put(id, linksTitles);
			wikiId21stSectLinks.put(id, outFirstLinks);
			wikiId21stSectLinksText.put(id, outFirstLinksText);
			values4Db+="','link primo paragrafo','";
			
			
			
			//finito di annotare tutto posso salvare a db sia le annotazioni che i dati relativi alle pagine wikipedia
			
			//vrsione con plainText
			//values4Db+=pp.getText().replace("'", "")+"'"+",'"+wikiId2Title.get(id).replace("'", "[codiceapostrofo]")+"'";
			//db.insert("cache", "idwikipage,textlength,firstsectionlength,wikilinkid,wikilinkfirstsectionid,wikilinkfirstparagid,plaintext,pagename", values4Db);
			values4Db+=wikiId2Title.get(id).replace("'", "[codiceapostrofo]")+"'";
			//System.out.println(values4Db);
			db.insert("cache", "idwikipage,textlength,firstsectionlength,wikilinkid,linkstext,wikilinkfirstsectionid,wikilinkfirstparagid,pagename", values4Db);
			
			
		}
		
		Set<String> loNouns = new TreeSet<String>();
		String nouns4Db =",'";
		loNouns = getLoNouns(lo);
		String app;
		fileName2Nouns.put(lo, loNouns);
		for(String s : loNouns){
			app=s.replace("'", "[codiceapostrofo]");
			nouns4Db += app.replace("-", "[codiceatrattino]")+"-";
		}
			
		nouns4Db+="'";
		
		values4Db = "'"+lo.replace("\\", "\\\\")+"','";
		//Rimuovo i Los non validi (anche dalla mappa)
		copyLos.removeAll(wrongLos);
		annotations.put(lo,copyLos);
		for(Integer inte : copyLos){
			values4Db+=inte+"-";
		}
		values4Db+="'";
		
		db.insert("annotation", "loname,annotation,nouns", values4Db+nouns4Db);
	}
	
	
	//funzione getId presa dal vecchio parser
	//preso un titolo di un articolo wikipedia
	
	/*
	 * non pi� usate perche per ogni link doveva fare una chiamata a wikipedia
	 * per sapere il l'id della pagina relativa.
	 * es.un lo aveva associati 10 topic e ogni topic aveva 60 link (in media anche pi�)
	 * 10*60 = 600 chiamate per un solo LO
	 * pensando a dataset di 2000 LO
	 * 2000*600 = pi� di un milione di chiamate (1200000) a wikipedia
	 
	public int getId(String title) throws Exception{
		Integer id=0;
		String[] m;
		
		if (title2WikiId.containsKey(title)) {
			id = title2WikiId.get(title);
			System.out.println(title2WikiId.get(title));
		}
		else{
			try{
				id = Integer.parseInt(wiki.getPageInfo(title).get("pageid").toString());
				title2WikiId.put(title, id);
			}
			catch(Exception e){
				id=-1;
			}
			
		}
		return id;
	}
	
	
	public int OldgetId(String title) throws IOException {
		int id = -1;
		
		Map m1 = new HashMap();
		if (title2WikiId.get(title) != null) {
			id = title2WikiId.get(title);
		} else {
			if(id != -1)
					m1 = wiki.getPageInfo(title);
			try {
				if (Long.class.isInstance(m1.get("pageid"))) {
					id = (int) ((Long) m1.get("pageid")).longValue();
					title2WikiId.put(title, Integer.valueOf(id));
					
				} else {
					// wiki page still to be created
					title2WikiId.put(title, Integer.valueOf(-1));
					
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return id;
	}
	
	*/
	
	
	//leggo testo di un file e torno una String in UTF_8
	static String readFile(String path) 
			  throws IOException 
			{
			  byte[] encoded = Files.readAllBytes(Paths.get(path));
			  return new String(encoded, StandardCharsets.UTF_8);
			}
	
	
	public void evaluation(){
		
		//riempio los con lista di nomi dei file nella directory DOC_DIR definita nella classe
				List<String> los = new ArrayList<String>();
				
				File folder = new File(DOC_DIR);
				File[] listOfFiles = folder.listFiles();
				for (int i = 0; i < listOfFiles.length; i++) {
					if (listOfFiles[i].isFile()) {
						if (listOfFiles[i].getName().startsWith("."))
							continue;
					los.add(listOfFiles[i].getAbsolutePath());
					}
					
				}
				if(debug)
					System.out.println("finito di creare array nomi lo");
		//fine riempimento los
		
			
				
		//prendo le annotazioni per i lo e li inserisco nella mappa annotations
		//salvando a db le cache
				
				for(String s : los){
					if(debug)
						System.out.println("annoto il file "+s);
					try{
						if(annotations.get(s)==null)
						annotate(s);
					}
					catch(Exception e){
						System.out.println(e.toString());
						e.printStackTrace();
					}
					
					
					if(debug)
						System.out.println("finito di annotare il file "+s);
				}
				
				if(debug)
					System.out.println("finito di annotare i file inizio estrazione features");
				
				PrintWriter r=null;
				PrintWriter weka=null;
				
				//ex H2()
				double avgLength1=0;
				double avgLength2=0;
				
				//ex H3() e H4()
				Set<String> firstSectLink1 = null;
				Set<String> firstSectLink2 = null;
				Set<String> titleAnnotation = new TreeSet<String>();
				Set<String> titleAnnotation2 = new TreeSet<String>();
				Set<String> titleLinks =  new TreeSet<String>();
				Set<String> titleLinks2 =  new TreeSet<String>();
				Set<String> titleAnnotation21 = new TreeSet<String>();
				Set<String> titleAnnotation22 = new TreeSet<String>();
				Set<String> titleLinks21 =  new TreeSet<String>();
				Set<String> titleLinks22 =  new TreeSet<String>();
				
				double sizeH3Lo1 =0;
				double sizeH3Lo2 =0;
				double sizeH4Lo1 =0;
				double sizeH4Lo2 =0;
				
				//ex H5()
				Set<String> lo1Nouns = new TreeSet<String>();
				Set<String> lo2Nouns = new TreeSet<String>();
				Set<String> lo1Intersectlo2 = new TreeSet<String>();
				Set<String> lo1UnionLo2 = new TreeSet<String>();
				//ex H6()
				double avgFirstLength1=0;
				double avgFirstLength2=0;
				
				//exH7 ancora da implementare
				Set<String> linkInText12 = new TreeSet<String>();
				Set<String> linkInText21 = new TreeSet<String>();
				double size12;
				double size21;
				
				
		//inizializzo il file arff per weka e apro il file per R
				try{
					r = new PrintWriter("./filesPerClassificazione/"+NOME_FILE_R);
					weka = new PrintWriter("./filesPerClassificazione/"+NOME_FILE_WEKA);
					initArff(weka);
				}
				catch(Exception e){
					System.out.println("errore nell'apertura degli scrittori per i file");
					e.printStackTrace();
				}
				
		//inizio la valutazione vera tra le coppie di lo controllandoli tutti con tutti
				int c = 0;
				for(String s : los){
					for(String z : los){
						System.out.println("\n\n\nTotale iterazioni:" + los.size()*los.size() + 
											" Iterazione n°: " + ++c + "\n\n\n");
						//System.out.println(s+"\t"+z);
						//non confronto i lo con loro stessi
						System.out.println(s+"  "+z);
						if(s.compareTo(z)==0)
							continue;						
						
						
						//inizializzo le variabili per questi lo e
						//calcolo lunghezze medie dei topic associati
						//calcolo le lunghezze medie dei topic associati
						//Ex H2() e H6()
						
						avgLength1=0;
						avgLength2=0;
						avgFirstLength1=0;
						avgFirstLength2=0;
						
						firstSectLink1 = new HashSet<String>();
						firstSectLink2 = new HashSet<String>();
						
						titleLinks =  new TreeSet<String>();
						titleLinks2 =  new TreeSet<String>();
						titleLinks21 =  new TreeSet<String>();
						titleLinks22 =  new TreeSet<String>();
						
						titleAnnotation = new TreeSet<String>();
						titleAnnotation2 = new TreeSet<String>();
						titleAnnotation21 = new TreeSet<String>();
						titleAnnotation22 = new TreeSet<String>();
						
						linkInText12 = new TreeSet<String>();
						linkInText21 = new TreeSet<String>();
						
						//provo a scartare i topic presenti in entrambi i lo
						Set<Integer> topic1 = new TreeSet<Integer>();
						Set<Integer> topic2 = new TreeSet<Integer>();
						
						topic1=annotations.get(s);
						topic1.removeAll(annotations.get(z));
					
						topic2=annotations.get(z);
						topic2.removeAll(annotations.get(s));
						
						//estrazione valori per tutti i topic di lo1
						for(Integer i : topic1){
							
							avgLength1+=wikiId2Length.get(i);
							avgFirstLength1+=wikiId21stSectLength.get(i);
							
							for(String y : wikiId2LinksTitle.get(i) )
								firstSectLink1.add(y);
							
							//dati per h2e4 in modo da non ripetere i cicli e velocizzare
							//per tutti i topic associati al LO1 prendo i titoli delle pagine annotate
							//e tutti i titoli dei link
							
							titleAnnotation.add(wikiId2Title.get(i).toLowerCase());
							for(String x : wikiId2LinksTitle.get(i))
								titleLinks.add(x.replace("_", " ").toLowerCase());
							
							titleAnnotation21.add(wikiId2Title.get(i).toLowerCase());
							for(String x : wikiId2AllLinksText.get(i))
								titleLinks21.add(x.replace("_", " ").toLowerCase());
							
						}
						if(avgLength1 > 0)
							avgLength1=avgLength1/annotations.get(s).size();
						if(avgFirstLength1 > 0 )	
							avgFirstLength1=avgFirstLength1/annotations.get(s).size();
						
						
						//estrazione valori per tutti i topic di lo2
						for(Integer i : topic2){
							
							System.out.println(wikiId2Title.get(i));
							avgLength2+=wikiId2Length.get(i);
							avgFirstLength2+=wikiId21stSectLength.get(i);
							
							for(String y : wikiId2LinksTitle.get(i) )
								firstSectLink2.add(y);
							
							//dati per h2e4 in modo da non ripetere i cicli e velocizzare
							//per tutti i topic associati al LO2 prendo i titoli delle pagine annotate
							//e tutti i titoli dei link
							
							titleAnnotation2.add(wikiId2Title.get(i).toLowerCase());
							for(String x : wikiId2LinksTitle.get(i))
								titleLinks2.add(x.replace("_", " ").toLowerCase());
							
							titleAnnotation22.add(wikiId2Title.get(i).toLowerCase());
							for(String x : wikiId2AllLinksText.get(i))
								titleLinks22.add(x.replace("_", " ").toLowerCase());
						}
						if(avgLength2 > 0)
							avgLength2=avgLength2/annotations.get(z).size();
						if(avgFirstLength2 > 0)	
							avgFirstLength2=avgFirstLength2/annotations.get(z).size();
						
						//fine ex H2() e H6()
						
						
						//calcolo quanti link nella prima sezione di Lo1 
						//hanno come destinazione LO2 e viceversa
						//ex H3()
						
						
						titleLinks.retainAll(titleAnnotation2);
						titleLinks2.retainAll(titleAnnotation);
						
						sizeH3Lo1 = titleLinks.size();
						sizeH3Lo2 = titleLinks2.size();
						
						//calcolo quanti link nei topic di Lo1 
						//hanno come destinazione LO2 e viceversa
						//ex H4()
						
						//mi duplico i valori pe h7()
						
						linkInText12.addAll(titleLinks21);
						linkInText21.addAll(titleLinks22);
						
						//fine valori h7()
						
						titleLinks21.retainAll(titleAnnotation22);
						titleLinks22.retainAll(titleAnnotation21);
						
						sizeH4Lo1 = titleLinks21.size();
						sizeH4Lo2 = titleLinks22.size();
						//fine ex H3() e ex H4()
						
						
						//calcolo i nouns di LO1, LO2, l'intersezione e l'unione
						//ex H5()
						double nouns1=0;
						double nouns2=0;
						double intersect=0;
						double union = 0;
						
						try{
							if(fileName2Nouns.get(s)==null)
								lo1Nouns = getLoNouns(s);
							else{
								
								lo1Nouns = fileName2Nouns.get(s);
							}
							if(fileName2Nouns.get(z)==null)
								lo2Nouns = getLoNouns(z);
							else{
								
								lo2Nouns = fileName2Nouns.get(z);
							}
							
							
							nouns1 = lo1Nouns.size();
							nouns2 = lo2Nouns.size();
							
							lo1Intersectlo2.clear();
							lo1Intersectlo2.addAll(lo1Nouns);
							lo1Intersectlo2.retainAll(lo2Nouns);
							intersect = lo1Intersectlo2.size();
							
							lo1UnionLo2.clear();
							lo1UnionLo2.addAll(lo1Nouns);
							lo1UnionLo2.addAll(lo2Nouns);
							union = lo1UnionLo2.size();
							
						}
						catch(Exception e){
							System.out.println("errore nel calcolo dei nouns");
							e.printStackTrace();
						}
						
						//fine ex h5()
						
						
						//confronto i testi dei link di Lo1 con il testo di Lo2 e viceversa
						//ex H7()
						
						
						linkInText12.retainAll(lo2Nouns);
						linkInText21.retainAll(lo1Nouns);
						
						size12=linkInText12.size();
						size21=linkInText21.size();
						
						//fine exH7()
						
						
						//controllo il file expected e inserisco il valore della classe 1,2 o 3 
						int key = 3;
						try{
							Set<String> dipendenze = readDependences(EXPECTED_PATH); 
							
							if(dipendenze.contains(s+"\t"+z))
								key=1;
							else if(dipendenze.contains(z+"\t"+s))
								key=2;
							else
								key=3;
							
						}
						catch(Exception e){
							System.out.println("non riesco a prendere le dipendenze dal file expected");
							e.printStackTrace();
						}
						
						//fine inserimento classe da expected
						
						
						
						
						
						
						//scrivo i valori calcolati nei file con la funzione writeDataLine
						
						writeDataLine(annotations.get(s).size()+","+annotations.get(z).size()+","+avgLength1+","+avgLength2+","+avgFirstLength1+","+avgFirstLength2+","+sizeH3Lo1+","+sizeH3Lo2+","+sizeH4Lo1+","+sizeH4Lo2+","+nouns1+","+nouns2+","+intersect+","+union+","+size12+","+size21+","+key,weka,r);
						
					}
				}
				
				//chiudo gli scrittori su file weka e R
				
				r.close();
				weka.close();
				
				if(debug)
					System.out.println("finito estrazione features");
				
	}
	
	
	//risolto bug testi troppo lunghi da annotare dividendo l�inpt in spezzoni da 5000 caratteri
	//e unendo gli input delle varie iterazioni
	
	
	public Set<String> getLoNouns(String lo) throws IOException {
		Set<String> ret = new HashSet<String>();
		String text = "";
		int maxChars = 100;
		
		//se il testo è troppo longo va in errore il posTagger per troppa memoria usata
		//divido il lavoro in task di massimo 300
		
		
		try{
			text = readFile(lo);
		}
		catch(Exception e){
			System.out.println("errore durante lettura del file");
			System.out.println(e.toString());
		}
		int volte=text.length()/maxChars;
		int lower=0;
		int upper=0;
		if(text.length() > maxChars){
			lower=0;
			upper=maxChars;
		}
		else{
			lower=0;
			upper=text.length()-1;
			volte=1;
		}
		
		for (int y=0;y<volte;y++){
			//replace di caratteri che sono stati persi nella conversione in UTF-8 del file e che 
			//danno problemi al tagger
			text = text.replace("\uFFFD", "[NOTUTF8]");
			List<List<HasWord>> sentences = MaxentTagger.tokenizeText(new StringReader(text.substring(lower, upper)));
			for (List<HasWord> sentence : sentences) {
				List<TaggedWord> tSentence = posTagger.tagSentence(sentence);
				Iterator<TaggedWord> twIter = tSentence.iterator();
				while (twIter.hasNext()) {
					TaggedWord taggedWord = (TaggedWord) twIter.next();
					if (taggedWord.tag().startsWith("NN"))
						ret.add(taggedWord.word().toLowerCase());
				}
				
			}
			lower+=maxChars;
			if(text.length()>upper+maxChars)
				upper+=maxChars;
			else
				upper=text.length()-1;
		}
		return ret;
	}
	
	
	
	
	
	
	
	
	public void writeDataLine(String x,PrintWriter weka,PrintWriter r){
		try{
			weka.print(x+"\n");
			r.print(x+"\n");
			
		}
		catch(Exception e){
			System.out.print("errore nell'aggiunta di una linea ai file Dati dei classificatori");
			e.printStackTrace();
		}
		
	}
	
	
	
	//creazione file arff e definizione struttura. dopo aver chiamato questo
	//metodo basta inserire le varie righe con i valori per ogni istanza
	
	public void initArff(PrintWriter weka){
		
		try{
			
			weka.println("% 1. Title: Learning Objects sequencing\n"+
					"% \n"+
					"% 2. Sources:\n"+
					"%      (a) Creator:Carlo De Medioi\n"+
					"%      (b) Date: August 2016\n"+
					"% 3. Classes:\n"+
					"% (a) Class 1: dependence lo1 -> lo2\n"+
					"% (b) Class 2: dependence lo2 -> lo1\n"+
					"% (c) Class 3: dependence unknow\n"+
					"%\n"+
					"@RELATION losSequencing\n"+
					//numero di topic associati ai LO 
					"@ATTRIBUTE numberTopicsLo1 NUMERIC\n"+
					"@ATTRIBUTE numberTopicsLo2 NUMERIC\n"+
					//lunghezze medie dei topic associati
					"@ATTRIBUTE textLengthLo1 NUMERIC\n"+
					"@ATTRIBUTE textLengthLo2 NUMERIC\n"+
					//lunghezze medie prime sezioni topic associati
					"@ATTRIBUTE firstSectLengthLo1 NUMERIC\n"+
					"@ATTRIBUTE firstSectLengthLo2 NUMERIC\n"+
					//numero di link di Lo1 nella prima sezione con collegamenti a topic di Lo2 e viceversa
					"@ATTRIBUTE firstSectLinkLo1ToLo2 NUMERIC\n"+
					"@ATTRIBUTE firstSectLinkLo2ToLo1 NUMERIC\n"+
					//come sopra ma su tutti i link della pagina
					"@ATTRIBUTE allLinkLo1ToLo2 NUMERIC\n"+
					"@ATTRIBUTE allLinkLo2ToLo1 NUMERIC\n"+
					//numero nouns lo1 e lo2 pi� la loro intersezione ed unione
					"@ATTRIBUTE lo1Nouns NUMERIC\n"+
					"@ATTRIBUTE lo2Nouns NUMERIC\n"+
					"@ATTRIBUTE lo1IntersectLo2 NUMERIC\n"+
					"@ATTRIBUTE Lo1UnionLo2 NUMERIC\n"+
					//numero di link il cui testo � presente nei nouns dell'altro lo e viceversa
					"@ATTRIBUTE linkInNouns12 NUMERIC\n"+
					"@ATTRIBUTE linkInNouns21 NUMERIC\n"+
					//classe attesa
					"@ATTRIBUTE expected_class {1,2,3}\n"+
					"\n"+
					"@DATA");
			
			
			
		}
		catch(Exception e){
			System.out.println("errore nell'inizializzazione file arff per weka");
			e.printStackTrace();
		}
	}
	
	//riprende le entries salvate a db e le inserisce nelle mappe
	public void readCache(){
		
		
		
		try{
			ResultSet rs = db.getTable("cache");
			while (rs.next()) {
				// Read values using column name
				String idwikipage = rs.getString("idwikipage");
				String textlength = rs.getString("textlength");
				String firstsectionlength = rs.getString("firstsectionlength");
				String wikilinkid = rs.getString("wikilinkid");
				String wikilinkfirstsection = rs.getString("wikilinkfirstsectionid");
				String linkstext = rs.getString("linkstext");
				//da rivedere come inserirlo, molti caratteri danno problemi al db
				//String plaintext = rs.getString("plaintext");
				//wikiId2Text.put(id, plaintext);
				
				String pagename = rs.getString("pagename");
				Integer id = new Integer(idwikipage);
				Integer firstlength = Integer.parseInt(firstsectionlength);
				Integer length = new Integer(textlength);
				wikiId2Title.put(id, pagename.replace("[codiceapostrofo]", "'"));
				title2WikiId.put(pagename, id);
				
				wikiId21stSectLength.put(id, firstlength);
				wikiId2Length.put(id, length);
				
				Set<String> allLink = new HashSet<String>();
				String app2;
				for(String s : wikilinkid.split("-")){
					if(s.compareTo("")!=0){
						app2 = s.replace("[codiceapostrofo]", "'");;
						allLink.add(app2.replace("[codiceatrattino]", "-"));
					}
					
				}
				wikiId2AllLinksText.put(id,allLink);
				
				/*
				allLink = new HashSet<Integer>();
				for(String s : wikilinkfirstsection.split("-")){
					if(s.compareTo("")!=0){
						Integer idLink = Integer.parseInt(s);
						allLink.add(idLink);
					}
					
				}
				wikiId21stSectLinks.put(id,allLink);
				*/
				Set<String> titleLink = new HashSet<String>();
				String app;
				for(String s : wikilinkfirstsection.split("-")){
					if(s.compareTo("")!=0){
						app=s.replace("[codiceapostrofo]", "'");
						titleLink.add(app.replace("[codiceatrattino]", "-"));
					}
					
				}
				wikiId2LinksTitle.put(id,titleLink);
				
				
				Set<String> linksText = new HashSet<String>();
				String app3;
				for(String s : linkstext.split("-")){
					if(s.compareTo("")!=0){
						app3=s.replace("[codiceapostrofo]", "'");
						linksText.add(app3.replace("[codiceatrattino]", "-"));
					}
					
				}
				wikiId2AllLinksTextTrue.put(id,titleLink);
				
			}
			
			ResultSet rs2 = db.getTable("annotation");
			while (rs2.next()) {
				String loName = rs2.getString("loname");
				String annotazioni =rs2.getString("annotation");
				String nouns = rs2.getString("nouns");
				
				Set<Integer> ann = new HashSet<Integer>();
				for(String x : annotazioni.split("-")){
					if(x.compareTo("")!=0)
						ann.add(Integer.parseInt(x));
						
				}
				annotations.put(loName, ann);
				
				Set<String> noun = new TreeSet<String>();
				String app4;
				for(String s : nouns.split("-")){
					if(s.compareTo("")!=0){
						app4=s.replace("[codiceapostrofo]", "'");
						noun.add(app4.replace("[codiceatrattino]", "-"));
					}
						
				}
				fileName2Nouns.put(loName, noun);
				
			}
			
			
			db.closeConnection();
			
		}
		catch(Exception e){
			System.out.println("errore nel ricostruire la cache");
			e.printStackTrace();
		}
		
		
		
	}
	
	public Set<String> readDependences(String fn) throws IOException {
		Set<String> dependences = new HashSet<String>();
		BufferedReader br = new BufferedReader(new FileReader(fn));
		try {
			String line = br.readLine();

			while (line != null) {
				// remove multiples tabs
				line = line.replaceAll("\\t+", "\t");
				dependences.add(line.trim());
				line = br.readLine();
			}
		} finally {
			br.close();
		}
		return dependences;
	}
	
	
	public static void main(String[] args){
		
		
		Sequencer sequencer = new Sequencer();
		
		//leggo e riempio le mappe leggendo la cache a db
		sequencer.readCache();
		
		//faccio partire la evaluetion che annotera i lo non ancora annotati
		//ed inserir� i valori per le istanze nei file per i classificatori
		sequencer.evaluation();
		
	}
	
}
