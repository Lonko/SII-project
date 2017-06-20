package tools;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;


public class DBmanager {
	
	Connection conn=null;
	String connessione="";
	String user="";
	String pass="";
	public DBmanager(String connessione,String user,String pass){
		try{
			Class.forName("com.mysql.jdbc.Driver");
			this.connessione=connessione;
			this.pass=pass;
			this.user=user;
		}
		catch(Exception e){
			System.out.println("errore nella connessione al database");
			System.out.println(e.toString());
		}

	    
	}
	
	public void insert(String table,String campi,String values){
		try{
			System.out.println("Connecting to database...");
		    //conn = DriverManager.getConnection(connessione,user,pass);
			Statement stmt = null;
			stmt = this.conn.createStatement();
			String sql;
		    sql = "INSERT INTO "+table+"("+campi+") VALUES("+values+")";
		    stmt.executeUpdate(sql);
		    //conn.close();
		}
		catch(Exception e){
			System.out.println("errore nella insert a db");
			System.out.println("\n\nTable:"+table+"\n\n"+ values + "\n\n\n\n");
			System.out.println(e.toString());
		}
		
	}
	
	
	public ResultSet getTable(String table,String where){
		ResultSet rs=null;
		try{
			System.out.println("Connecting to database...");
		    //conn = DriverManager.getConnection(connessione,user,pass);
			Statement stmt = null;
			stmt = this.conn.createStatement();
			String sql;
		    sql = "SELECT * FROM "+table+" WHERE "+where;
		    rs = stmt.executeQuery(sql);
		    //conn.close();
		    
		}
		catch(Exception e){
			System.out.println("errore nella getTable  a db");
			System.out.println(e.toString());
		}
		return rs;
	}
	public ResultSet getTable(String table){
		ResultSet rs=null;
		try{
			System.out.println("Connecting to database...");
		    //conn = DriverManager.getConnection(connessione,user,pass);
			Statement stmt = null;
			stmt = this.conn.createStatement();
			String sql;
		    sql = "SELECT * FROM "+table;
		    rs = stmt.executeQuery(sql);
//		    conn.close();
		    
		}
		catch(Exception e){
			System.out.println("errore nella getTable  a db");
			System.out.println(e.toString());
		}
		return rs;
	}
	
	public void openConnection(){
	    try {
			this.conn = DriverManager.getConnection(this.connessione,this.user,this.pass);
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
		
	public void closeConnection(){
		try{
			conn.close();
		}
		catch(Exception e ){
				e.printStackTrace();
			}
		
		
	}
	
	
	public static void main(String[] args){
		DBmanager db = new DBmanager("jdbc:mysql://localhost/wikisequencer","root","mysql");
		System.out.println(db.getTable("cache").toString());
	}
}
