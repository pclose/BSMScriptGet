/*  BSMScriptGet - pete 2015-09-30
 *  
 *  Usage: java BSMScriptGet <database> <BSM_MANAGEMENT password> <RTSM_DATA password> [<fetch-option>] [<destination>]
 *  
 *  This program connects to the BSM management database and retrieves the 
 *  BPM script repository contents.
 *  Running this depends on ojdbc6.jar from Oracle 
 */
        
import oracle.jdbc.pool.OracleDataSource;

import java.io.IOException;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;

import java.sql.Connection;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class BSMScriptGet {

    private Connection  mgmtDB;
    private Connection  rtsmDB;
    private int         fetch;
    private File        destination;
    private String      destination_name;
    private String      MGMT_SCHEMA = "BSM_MANAGEMENT";
    private String      RTSM_SCHEMA = "RTSM_DATA";
    
    private ArrayList<BPMScript> apps =            new ArrayList<BPMScript>();
    private HashMap<Integer, ScriptEnt> scripts =  new HashMap<Integer, ScriptEnt>();
    
    private static String[] type_str = new String[]{
        "list scripts and their versions (default)",
        "fetch all scripts in use",
        "fetch highest version of scripts in use",
        "fetch highest version of all scripts"
    };

    public static void main(String[] args) {
    
        String db = "";
        String mgmtPW;
        String rtsmPW;        
        String destination;
        int    fetch = 0;
        
        // print help and exit if -h or --help are in args
        for (String e : args) { 
            if (e.matches("-h|--help")) {
                prUsage();
                System.exit(0);
            }
        }
        
        // lazy- just base parsing on number of arguments
        if (args.length < 3) {
            prUsage();
            System.exit(1);
        }
        db = args[0];
        mgmtPW = args[1];
        rtsmPW = args[2];
        
        // specify the fetch command via integer
        try {
            if (args.length >= 4) fetch = Integer.parseInt(args[3]);
        } catch (java.lang.NumberFormatException ex) {
            prUsage();
            System.exit(1);
        }
        
        // destination directory
        if (args.length >= 5) destination = args[4];
        else destination = "BSMScripts";

        // pass args to constructor that will do the work
        BSMScriptGet _ = new BSMScriptGet(db, mgmtPW, rtsmPW, fetch, destination);
        
    }
    
    
    
    /* prUsage- prints usage information
     * */
    private static void prUsage() {
        System.out.println("Usage: java BSMScriptGet <database> <BSM_MANAGEMENT password> <RTSM_DATA password> [<fetch-option>] [<destination>]");
        System.out.println("<database> format = hostname:1521:bsminstance");
        System.out.println("<fetch-option> = ");
        for (int i = 0; i < type_str.length; i++)
            System.out.println(String.format("\t\"%d\" = %s",i,type_str[i]));
        System.out.println("<destination> = relative directory to save zips");
    }
    
    
    
    /* BSMScriptGet- private constructor call from main 
     * does all the run logic */
    private BSMScriptGet(String db, String mgmtPW, String rtsmPW, int fetch, String destination) {
        
        this.destination_name = destination;
        this.fetch = fetch;
        
        try {
        
            System.out.print("Setting up database connection...");
            setupConn(db, mgmtPW, rtsmPW);
            System.out.print("Done!\n");
        
            // list scripts and their versions (default)
            if (fetch == 0) {
                System.out.println("querying database");
                execApp();
                execAllSR();
                System.out.println(String.format("starting to: \"%s\"",type_str[fetch]));
                printCurrentAppsAndScripts(true);
                
            // fetch all scripts in use
            } else if (fetch == 1) {
                System.out.println("querying database");
                execApp();
                execAllSR();
                System.out.println(String.format("starting to: \"%s\"",type_str[fetch]));
                dlScriptsInUse();
                
            // fetch highest version of scripts in use
            } else if (fetch == 2) {
                System.out.println("querying database");
                execApp();
                execAllSR();
                System.out.println(String.format("starting to: \"%s\"",type_str[fetch]));
                dlScriptsHighestVersionsInUse();
                
            // fetch highest version of all scripts
            } else if (fetch == 3) {
                System.out.println("querying database");
                execAllSR();
                System.out.println(String.format("starting to: \"%s\"",type_str[fetch]));
                dlScriptsHighestVersions();
                
            // print usage and exit
            } else {
                prUsage();
                System.exit(1);
            }
            
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
        }
        
    }
    
    
    
    /* BSMScriptGet- public constructor doesn't do anything
     * doubtful that this would ever be needed */
    public BSMScriptGet() {}
    
    
    
    /* execApp - query database and populate array of BPMScript objects
     * App data includes the defined applications (no script repo) */
    private void execApp() throws SQLException, IOException {
        
        //query list of BPM scripts
        ResultSet rset = mgmtDB.createStatement().executeQuery("select "+
        "id,cmdb_btf_id,cmdb_app_id,crs_script_id,script_id,script_name,"+
        "crs_version_id,is_deleted from EUMBPM_SCRIPTS");
        
        //create object for each record
        while (rset.next())
            apps.add(new BPMScript(rset));
            
        //tie the RTSM data (eg. ci name) to the scripts
        PreparedStatement BAstmt  = rtsmDB.prepareStatement("select a_name "+
        "from BUSINESS_APPLICATION_1 where cmdb_id = ?");    
        PreparedStatement BTFstmt = rtsmDB.prepareStatement("select a_name "+
        "from BUSINESS_TRANSACTION_FLOW_1 where cmdb_id = ?");
        for (BPMScript e : apps) {
            ResultSet r;
        
            BAstmt.setBytes(1, e.cmdb_app_id);
            r = BAstmt.executeQuery();
            if (r.next())
                e.app_name = r.getString("a_name");
        
            BTFstmt.setBytes(1, e.cmdb_btf_id);
            r = BTFstmt.executeQuery();
            if (r.next()) 
                e.btf_name = r.getString("a_name");
        
        }
        
        //query the application and business transaction flow state
        PreparedStatement appStatus = mgmtDB.prepareStatement("select state "+
        "from EUMBPM_APPLICATIONS where CMDB_APP_ID = ?");
        PreparedStatement btfStatus = mgmtDB.prepareStatement("select state "+
        "from EUMBPM_BTFS where CMDB_BTF_ID = ?");
        for (BPMScript e : apps) {
            ResultSet r;
            
            appStatus.setBytes(1, e.cmdb_app_id);
            r = appStatus.executeQuery();
            if (r.next()) /*state==1 is an assumption but seems to work */
                e.app_is_enabled = r.getInt("state") == 1 ? true : false;
                
            btfStatus.setBytes(1, e.cmdb_btf_id);
            r = btfStatus.executeQuery();
            if (r.next())
                e.btf_is_enabled = r.getInt("state") == 1 ? true : false;
            
        }
        
    }
    
    
    
    /* execAllSR - query database and populate array of ScriptRepo objects
     * ScriptRepo data includes only the script repository entries (no apps)*/
    private void execAllSR() throws SQLException, IOException {
    
        // queries for script repository tables
        ResultSet rset = mgmtDB.createStatement().executeQuery("select " +
        "entity_id, entity_name, entity_type, full_path, description from SR_ENTITY");
        PreparedStatement scriptVerQ = mgmtDB.prepareStatement("select "+
        "version_id, version_number, description, update_date, user_id from "+
        "SR_SCRIPT_VER where entity_id = ? order by version_number");
        
        while (rset.next()) {
            
            // type 2 are scripts, type 1 are folders.. that is all i know of
            if (rset.getInt("entity_type") == 2) {
                
                // "scripts" object is a HashMap 
                // key is the entity id
                int id = rset.getInt("entity_id");
                scripts.put(id, new ScriptEnt(rset));
                ScriptEnt _ = scripts.get(id);
                
                // for every script repository entity run an additional query
                // this adds a new vers
                scriptVerQ.setInt(1, _.entity_id);
                // for each record call addVersion() 
                ResultSet r = scriptVerQ.executeQuery();
                while (r.next()) 
                    _.addVersion(r);
                
            }
        }
    
    }
    
    
    
    /* createDir - this silently creates a directory
     * would need to use J7 java.nio.files to do any error checking */
    private void createDir(String d) {
    
        destination = new File(d);
        boolean stat = destination.mkdirs();
    
    }
    
    
    
    /* printCurrentAppsAndScripts - prints script repo objects for each app
     * in a tree like fashion */
    private void printCurrentAppsAndScripts() {
    
        Collections.sort(apps, new BPMScriptComp());
            
        for (BPMScript e : apps) {
            if (e.is_deleted) continue;
            
            System.out.println(e.app_name + " - Enabled: "+e.app_is_enabled);
            System.out.println("\t"+ e.btf_name + " - Enabled: "+e.btf_is_enabled);
            ScriptEnt s = scripts.get(e.crs_script_id);
            for (ScriptVer i : s.script_ver) {
                if (i.id == e.crs_version_id) {
                    System.out.println("\t\t" + i.str + " " + s.path + s.entity_name + " " + i.date );
                }
            }
        }
    
    }
    
    
    
    /* printCurrentAppsAndScripts- prints script repo objects for each app
     * in a csv format */
    private void printCurrentAppsAndScripts(Boolean t) {
    
        Collections.sort(apps, new BPMScriptComp());
        
        String sep = ",";
        
        System.out.println(
            "Business Application Name"             +sep+
            "Business Appliction Enabled"           +sep+
            "Business Transaction Flow Name"        +sep+
            "Business Transaction Flow Enabled"     +sep+
            "Deployed Script Version"               +sep+
            "Deployed Script Name"                  +sep+
            "Deployed Script Path in Repository"    +sep+
            "Date of Script Version"
        );
        
        for (BPMScript e : apps) {
            if (e.is_deleted) continue;
            
            System.out.print(
                e.app_name          +sep+
                e.app_is_enabled    +sep+
                e.btf_name          +sep+
                e.btf_is_enabled    +sep
            );
            
            ScriptEnt s = scripts.get(e.crs_script_id);
            for (ScriptVer i : s.script_ver) {
                if (i.id == e.crs_version_id) {
                    System.out.print(
                        i.str           +sep+ 
                        s.entity_name   +sep+
                        s.path          +sep+                        
                        i.date 
                    );
                }
            }
            System.out.println();
        }
    
    }
    
    
    
    /* dlScriptsInUse - loops through BPMScript objects and calls dlScript() 
     * on script versions that have the associated app/btf enabled */
    private void dlScriptsInUse() throws SQLException, Exception {
        
        createDir(this.destination_name);
        
        for (BPMScript e : apps) {
            
            if (e.is_deleted || !e.app_is_enabled || !e.btf_is_enabled) continue;
            
            String script_dir = this.destination_name + File.separator + 
                e.app_name + "-" + e.btf_name;
            
            String version_string = scripts.get(e.crs_script_id)
                .getScriptVer(e.crs_version_id).str;
            
            System.out.print(String.format(
                "Saving version %s of %s.zip to %s... ",
                version_string, e.script_name, script_dir
            ));
            
            if (dlScript(e.crs_script_id, e.crs_version_id, e.script_name, script_dir))
                System.out.print("Sucess!\n");
            else
                System.out.print("Failed\n");
            
        }
        
    }
    
    
    
    /* dlScriptsHighestVersionsInUse - loops through BPMScript objects and calls dlScript() 
     * on highest script versions that have the associated app/btf enabled */
    private void dlScriptsHighestVersionsInUse() throws SQLException, Exception {
        
        createDir(this.destination_name);
        
        for (BPMScript e : apps) {
            
            if (e.is_deleted || !e.app_is_enabled || !e.btf_is_enabled) continue;
            
            String script_dir = this.destination_name + File.separator + 
                e.app_name + "-" + e.btf_name;
            
            ScriptVer scr_ver = scripts.get(e.crs_script_id).getHighestScriptVer();
            
            System.out.print(String.format(
                "Saving version %s of %s to %s... ",
                scr_ver.str, e.script_name, script_dir
            ));
            
            if (dlScript(e.crs_script_id, scr_ver.id, e.script_name, script_dir))
                System.out.print("Sucess!\n");
            else
                System.out.print("Failed\n");
            
        }
        
    }
    
    
    
    /* dlScriptsHighestVersions - loops through ScripEnt objects and calls dlScript() 
     * on highest script versions */
    private void dlScriptsHighestVersions() throws SQLException, Exception {
        
        createDir(this.destination_name);
        
        for (ScriptEnt e : scripts.values()) {
            
            String script_dir = this.destination_name + File.separator + 
                e.path
                    .replace("\\root\\","")
                    .replace("\\",File.separator);
            
            ScriptVer scr_ver = e.getHighestScriptVer();
            
            System.out.print(String.format(
                "Saving version %s of %s to %s... ",
                scr_ver.str, e.entity_name, script_dir
            ));
            
            if (dlScript(e.entity_id, scr_ver.id, e.entity_name, script_dir))
                System.out.print("Sucess!\n");
            else
                System.out.print("Failed\n");
            
        }
        
    }
    
    
    
    /* dlScript- downloads the script specified from SR_SCRIPT_VER table
     * and then calls saveZipBlob() to save to the file and dir specified */
    private boolean dlScript(   int     script_id, 
                                int     script_version_id, 
                                String  script_name, 
                                String  dir
    ) throws SQLException, Exception {
    
        PreparedStatement scriptBlobQ = mgmtDB.prepareStatement("select "+
        "full_file from SR_SCRIPT_VER where entity_id = ? and version_id = ?");
            
        scriptBlobQ.setInt(1, script_id);
        scriptBlobQ.setInt(2, script_version_id);
        ResultSet rset = scriptBlobQ.executeQuery();
        
        return saveZipBlob(dir, script_name, rset);
    
    }
    
    
    
    /* saveZipBlob- saves the BLOB content of a SR_SCRIPT_VER query to a .zip file
     * */
    private boolean saveZipBlob(String dir, String script_name, ResultSet rset) {
        try {
            if (rset.next()) {
            
                createDir(dir);
            
                InputStream in = rset.getBinaryStream(1);
                
                String output_file_str = destination + 
                    File.separator + script_name + ".zip";
                File output_file = new File(output_file_str);
                
                OutputStream out = new FileOutputStream(output_file);
                byte[] buf = new byte[1024];
                int len;
                while((len=in.read(buf))>0){
                    out.write(buf,0,len);
                }
                out.close();
                in.close();
                
            } else {
                return false;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            return false;
        }
        return true;
    }
    
    
    
    /* setupConn - sets up the database connection objects 
     * mgmtDB,rtsmDB */
    private void setupConn(String db, String mgmtPW, String rtsmPW) throws SQLException, IOException {
    
        OracleDataSource ods = new OracleDataSource();
        ods.setURL("jdbc:oracle:thin:@" + db);
        ods.setUser(MGMT_SCHEMA);
        ods.setPassword(mgmtPW);
        
        mgmtDB = ods.getConnection();        
        
        ods = new OracleDataSource();
        ods.setURL("jdbc:oracle:thin:@" + db);
        ods.setUser(RTSM_SCHEMA);
        ods.setPassword(rtsmPW);
        
        rtsmDB = ods.getConnection();
        
    }
    
    
    
    /* BPMScript - this class corresponds to a record in the EUMBPM_SCRIPTS table
     * stores references to data from the RTSM_DATA schema */
    private class BPMScript {
    
        //bpm information
        public byte[]   id;
        public byte[]   cmdb_btf_id;
        public byte[]   cmdb_app_id;
        public int      crs_script_id;
        public int      script_id;
        public String   script_name;
        public int      crs_version_id;
        public Boolean  is_deleted;
        
        //rtsm information
        public String   app_name;
        public String   btf_name;
        
        //app and btf ci information
        public Boolean  app_is_enabled;
        public Boolean  btf_is_enabled;
        
    
        public BPMScript(ResultSet record) throws SQLException {
            
            id              = record.getBytes   ("id");
            cmdb_btf_id     = record.getBytes   ("cmdb_btf_id");
            cmdb_app_id     = record.getBytes   ("cmdb_app_id");
            crs_script_id   = record.getInt     ("crs_script_id");
            script_id       = record.getInt     ("script_id");
            script_name     = record.getString  ("script_name");
            crs_version_id  = record.getInt     ("crs_version_id");
            is_deleted      = record.getInt     ("is_deleted") > 0 ? true : false;
            
        }
        
        public String toString() {
            
            String id_str     = "";
            String btf_id_str = "";
            String app_id_str = "";
            
            //convert byte objects to the proper cmdb id representation
            for (int i = 0; i < id.length; i++) 
                id_str += String.format("%02x", id[i] & 0xFF);
            for (int i = 0; i < cmdb_btf_id.length; i++) 
                btf_id_str += String.format("%02x", cmdb_btf_id[i] & 0xFF);
            for (int i = 0; i < cmdb_app_id.length; i++) 
                app_id_str += String.format("%02x", cmdb_app_id[i] & 0xFF);

            String sep = ",";
            
            return 
                app_name                 + sep +
                btf_name                 + sep +
                script_name              + sep +
                script_id                + sep +
                crs_script_id            + sep +
                crs_version_id           + sep +
                is_deleted               + sep + 
                app_is_enabled           + sep + 
                btf_is_enabled           + sep + 
                id_str.toUpperCase()     + sep + 
                btf_id_str.toUpperCase() + sep + 
                app_id_str.toUpperCase() ;
                
        }
    
    
    }
    
    
    
    /* BPMScriptComp - a comparator class 
     * ensures the list of applications are sorted by app name
     * followed by business transaction flow name */
    private class BPMScriptComp implements Comparator<BPMScript> {
        public int compare(BPMScript a, BPMScript b) {
            int result = a.app_name.compareToIgnoreCase(b.app_name);
            if (result != 0)
                return result;
            else
                return a.btf_name.compareToIgnoreCase(b.btf_name);
        }
    }
    
    
    
    /* ScriptEnt - this class corresponds to a record in the SR_ENTITY table 
     * also holds functions for creating ScriptVer objects */
    private class ScriptEnt {
    
        public String               path;
        public int                  entity_id;
        public String               entity_name;
        public String               date;
        public ArrayList<ScriptVer> script_ver = new ArrayList<ScriptVer>();
        
        
        public ScriptEnt(ResultSet record) throws SQLException {
        
            path        = record.getString  ("full_path");
            entity_id   = record.getInt     ("entity_id");
            entity_name = record.getString  ("entity_name");
            
        }
        
        public void addVersion(ResultSet record) throws SQLException {
            
            ScriptVer _ = new ScriptVer();
            _.num       = record.getInt     ("version_number");
            _.id        = record.getInt     ("version_id");
            _.desc      = record.getString  ("description");
            _.date      = record.getString  ("update_date");
            _.user      = record.getInt     ("user_id");
            
            // formats the version string based on version_number 
            // eg. 9 = 1.0.9, 10 = 1.1.0, 99 = 1.9.9, 101 = 2.0.1
            // i don't really know what BSM would do after 99...
            _.str = String.format("%s.%s.%s",
                _.num / 100  + 1,
                _.num % 100 / 10 + 1,
                _.num % 10
            );
            
            if (_.desc == null || (_.desc.isEmpty() || _.desc.matches("^\\s+$"))) 
                _.desc = "no description provided";
            
            
            script_ver.add(_);
            
        }
        
        public ScriptVer getScriptVer(int id) {
            for (ScriptVer e : script_ver) {
                if (e.id == id) return e;
            }
            return null;
        }
        
        public ScriptVer getHighestScriptVer() {
            ScriptVer result = null;
            int i = 0;
            for (ScriptVer e : script_ver) {
                if (e.num > i) {
                    result = e;
                    i = result.num;
                }
            }
            return result;
        }
        
        public String toString() {
            
            String sep = ",";
            String result = path + sep + entity_name;
            for (ScriptVer e : script_ver) {
                result += "\n\t" + e;
            }
            return result;
            
        }
    
    }
    
    
    
    /* ScriptVer- this class represents a record in the SR_SCRIPT_VER table
     * */
    private class ScriptVer {
            
        public String   str;
        public String   desc;
        public int      num;
        public int      id;
        public String   date;
        public int      user;
        
        public ScriptVer() {};
        
        public String toString() {
            String sep = " ";
            return  str  + sep + 
                    date + sep + 
                    desc;
        }
            
    }
    

}
