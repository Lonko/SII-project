package tools;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xml.sax.InputSource;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;

import de.l3s.boilerpipe.BoilerpipeExtractor;
import de.l3s.boilerpipe.document.TextDocument;
import de.l3s.boilerpipe.extractors.CommonExtractors;
import de.l3s.boilerpipe.sax.BoilerpipeSAXInput;
import de.l3s.boilerpipe.sax.HTMLFetcher;


public class Downloader {

	//path per il file contentente la key per le API di google
	private static final String API_KEY_PATH = "../APIKey.txt";
	//path per il file contenente l'id del Custom Search Engine
	private static final String SE_ID_PATH = "../idSE.txt"; 
	//path per la cartella contenente i risultati delle query salvati
	private static final String RESULTS_PATH = "./dataset/queryResults/";
	//estrattore per boilerpipe
	private static final BoilerpipeExtractor extractor = CommonExtractors.DEFAULT_EXTRACTOR;
	
	//API Key
	private String key = "";
	//Search Engine ID
	private String id = "";
	
	
	public Downloader(){
		try{
			byte[] encodedKey = Files.readAllBytes(Paths.get(API_KEY_PATH));
			byte[] encodedId = Files.readAllBytes(Paths.get(SE_ID_PATH));
			this.key = new String(encodedKey, StandardCharsets.UTF_8);
			this.id = new String(encodedId, StandardCharsets.UTF_8);
		} catch (IOException e) {
			System.out.println("Errore nell'inizializzazione del downloader");
			e.printStackTrace();
		}
	}
	
	//restuisce il testo pulito della pagina associata all'url
	private String getPageText(URL url) throws Exception{
		
		InputSource is = HTMLFetcher.fetch(url).toInputSource();
	    BoilerpipeSAXInput in = new BoilerpipeSAXInput(is);
	    TextDocument doc = in.getTextDocument();

		return extractor.getText(doc);
	}
	
	//inoltra la query al search engine
	private List<URL> executeQuery(String query) throws Exception{
		//costruisco la richiesta e la invio
		HttpResponse<JsonNode> jsonResponse = Unirest.get("https://www.googleapis.com/customsearch/v1?"
				+ "q={query}&cx={cx}&lr={language}&num={num}&key={key}")
				.routeParam("query", query)
				.routeParam("cx", this.id)
				.routeParam("language", "lang_en")
				.routeParam("num", "10")
				.routeParam("key", this.key)
				.asJson();

		JSONObject responseBody = new JSONObject(jsonResponse.getBody().toString());
		
		//prendo le URLs
		JSONArray results = (JSONArray)responseBody.get("items");
		LinkedList<URL> urls = new LinkedList<>();
		for(int i = 0; i < results.length(); i++){
			String result = ((JSONObject) results.get(i)).getString("link");
			urls.add(new URL(result));
		}

		return urls;
	}
	
	private void saveToFile(List<String> results, String query){
		File dir = new File(RESULTS_PATH+query);
		dir.mkdir();
		int c = 0;
		Charset utf8 = StandardCharsets.UTF_8;

		try{
			for(String result : results){
				Files.write(Paths.get(dir.getPath()+"/"+c+".txt"), result.getBytes(utf8));
				c++;
			}
		}catch (Exception e) {
			System.out.println("Errore nella scrittura su file dei risultati della query: " + query);
			e.printStackTrace();
		}
	}
	
	public List<String> readFromFile(String query){
		List<String> results = new LinkedList<>();
		
		if(Files.exists(Paths.get(RESULTS_PATH+query))){
			File[] files = new File(RESULTS_PATH+query).listFiles();
			
			for(File file : files){
				try {
					byte[] encoded = Files.readAllBytes(Paths.get(file.toURI()));
					String text = new String(encoded, StandardCharsets.UTF_8);
					results.add(text);
				} catch (IOException e) {
					System.out.println("Errore nella lettura del file: " + file.getName());
					e.printStackTrace();
				}
			}
		}
		
		return results;
	}
	
	public List<String> fetchWebData(String query){
		List<URL> urls = new LinkedList<>();
		//controllo se i risultati sono già salvati su file
		System.out.println("QUERY: " + query);
		List<String> webPages = readFromFile(query);
		
		//se non lo erano eseguo la query
		if(webPages.size() == 0){
			try {
				urls = executeQuery(query);
			} catch (Exception e) {
				System.out.println("Errore nell'esecuzione della query");
				e.printStackTrace();
			}

			urls.stream().forEach(url -> {
				try {
					String pageText = getPageText(url);
					//aggiungo solo le pagine che non sono pdf
					if(!pageText.split("\n")[0].contains("PDF"))
						webPages.add(pageText);
				} catch (Exception e) {
					System.out.println("Errore nel fetching della pagina: " + url);
					e.printStackTrace();
				}
			});
			
			//salvo su file per evitare di dover ripetere più volte le stesse query
			saveToFile(webPages, query);
		}
		
		return webPages;
	}
	
	public static void main(String[] args) throws Exception{
		Downloader d = new Downloader();
//		URL u = new URL("https://www.khanacademy.org/science/biology/gene-expression-central-dogma/central-dogma-transcription/a/the-genetic-code-discovery-and-properties");
//		System.out.println(d.getPageText(u));
		
		List<String> r = d.fetchWebData("amino acides");
		
		r.stream().forEach(x -> System.out.println(x.toString()));
	}
}
