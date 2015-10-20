# BSMScriptGet
------------
This program connects to an Oracle based HP BSM management database and 
retrieves or lists the BPM script repository contents.
Running this depends on ojdbc6.jar from Oracle.

1. Download ojdbc6.jar from [Oracle](http://www.oracle.com/technetwork/apps-tech/jdbc-112010-090769.html).

2. compile
    
```
  $ javac -cp "./ojdbc6.jar" src/BSMScriptGet.java
```

3. execute
    
```
  $ java -cp "./ojdbc6.jar:./src" BSMScriptGet
  Usage: java BSMScriptGet <database> <BSM_MANAGEMENT password> <RTSM_DATA password> [<fetch-option>] [<destination>]
  <database> format = hostname:1521:bsminstance
  <fetch-option> = 
        "0" = list scripts and their versions (default)
        "1" = fetch all scripts in use
        "2" = fetch highest version of scripts in use
        "3" = fetch highest version of all scripts
  <destination> = relative directory to save zips
```

Alternatively you could compile using ant. 
This will also bundle an executable jar under ./build/bin/
    
```
  $ cd ant
  $ ant
```
