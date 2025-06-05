package org.csit;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class BFSTaskManager {
    private static final int MAX_PAGES = 10;
    private static final int MAX_THREADS = 10;
    private static final int TIMEOUT = 10000;

    // redis conf
    private static final String REDIS_HOST = "localhost";
    private static final int REDIS_PORT = 6379;
    //
    public static final int MAX_STRING_LENGTH = 10;
    private static final String REDIS_URL_ID_KEY = "crawler:url_id";
    // redis visited
    private static final String REDIS_VISITED_URLS_KEY = "crawler:visited";
    private static final String STEMMER_KEY = "stemmer:words";

    // mysql conf
    public static final String DB_URL = "jdbc:mysql://localhost:3306/crawler_db";
    public static final String DB_USER = "root";
    public static final String DB_PASSWORD = "123456";
    private static final String URLS_INFO_TABLE = "crawled_urls_info";
    public static final String INVERTED_INDEX_TABLE = "inverted_index_table";
    private static final String FORWARD_INDEX_TABLE = "forward_index_table";
    private static final int MAX_CONNECTION_ERROR_ALLOWED = 3;

    // bfs concurrent queue
    private JedisPool jedisPool;
    private Connection dbConnection;

    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final HashSet<String> stopwords = new HashSet<>();
    private int TARGET_COUNT;
    private int WORKER_THREADS;

    public static void main(String[] args){
    }
    public BFSTaskManager(String rootUrl,int limit,int threads) throws SQLException{
        queue.add(rootUrl);
        TARGET_COUNT = limit;
        WORKER_THREADS = threads;
        // read stopwords
        try (BufferedReader br = new BufferedReader(new FileReader("/Users/zxzhang/Desktop/lite-search/src/main/java/org/csit/stopwords.txt"))) {
            String line = null;
            while ((line = br.readLine()) != null) {
                stopwords.add(line.trim());
            }
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        // redis
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(MAX_THREADS);
        this.jedisPool = new JedisPool(poolConfig, REDIS_HOST, REDIS_PORT);
        // sql db, create based tables
        try {
            this.dbConnection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
            createTableIfNotExists();
            dbConnection.close();
        } catch (SQLException e){
            jedisPool.close();
            throw new SQLException(e);
        }

    }
    private void createTableIfNotExists() throws SQLException {
        // (id, url, time,,,, more data can be added)
        String sql1 = "CREATE TABLE IF NOT EXISTS " + URLS_INFO_TABLE + " (" +
                "id BIGINT PRIMARY KEY, " +
                "url VARCHAR(1000) NOT NULL, " +
                "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP)";

        String sql2 = "CREATE TABLE IF NOT EXISTS " + INVERTED_INDEX_TABLE + " (" +
                "word VARCHAR(20) NOT NULL, " +
                "did BIGINT NOT NULL, " +
                "freq BIGINT NOT NULL," +
                "PRIMARY KEY (word, did))";


        try (Statement stmt = dbConnection.createStatement()) {
            stmt.execute(sql1);
            stmt.execute(sql2);
            //stmt.execute(sql3);
        }
    }
    public void startProcessing(){
        Thread[] t = new Thread[WORKER_THREADS];
        // every thread should have their own db connections
        int i = 0;
        int error = 0;
        while(i < WORKER_THREADS){
            Connection c = null;
            Jedis j = null;
            if(error >= MAX_CONNECTION_ERROR_ALLOWED)
                return;
            try{
                j = jedisPool.getResource();
                c =  DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
            }catch (Exception e){
                if(j != null) j.close();
                try {if(c != null) {c.close();}}catch (Exception f){;}
                error++;
                System.out.println("Cannot conf properly for Thread i:"+i);
                continue;
            }
            t[i] = new Thread(new Worker(j,c));
            t[i].start();
            i++;
            System.out.println("Conf properly for Thread i:"+i);
        }
        synchronized (processedCount) {
            while (processedCount.get() < TARGET_COUNT) {
                try {
                    processedCount.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        for (int j = 0; j < WORKER_THREADS; j++) {
            t[j].interrupt();
        }
        // close db
        jedisPool.close();
        System.out.println("Page fetching finished.");
    }
    private void storeUrlToDatabase(long id, String url,Connection connection) {
        String sql = "INSERT INTO " + URLS_INFO_TABLE + " (id, url) VALUES (?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, id);
            pstmt.setString(2, url);
            pstmt.executeUpdate();
        }catch (SQLException e){
            System.out.println("Insert failure.");
        }
        finally {
            // :(
            return;
        }
    }
/*
    private void storeInvertedIndexToDatabase(String word, long did, int freq,Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        int batchSize = 1000;
        int count = 0;


        String sql = "INSERT INTO " + INVERTED_INDEX_TABLE + " (word, did, freq) VALUES (?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1,word);
            pstmt.setLong(2,did);
            pstmt.setLong(3, freq);
            pstmt.executeUpdate();
        }catch (SQLException e){
            System.out.println("Insert failure.");
        }
        finally {
            // :(
            return;
        }
    }
*/

    private boolean isValidUrl(String url) {
        return url != null && !url.isEmpty() &&
                (url.startsWith("http://") || url.startsWith("https://")) &&
                !url.contains("#") &&
                !url.matches(".*(\\.(css|js|gif|jpg|png|mp3|mp4|zip|gz))$");
    }
    class Worker implements Runnable {
        Jedis jedis;
        Connection dbConnection;
        StopStem stopStem = new StopStem("stopwords.txt");
        public Worker(Jedis j,Connection dbc){
            this.jedis = j;
            this.dbConnection = dbc;
        }
        @Override
        public void run() {
            try {
                while (processedCount.get() < TARGET_COUNT) {
                    String item = queue.take();
                    System.out.println(Thread.currentThread().getName() + " processing: " + item);
                    // find here
                    long docID;
                    Document doc;
                    // unique id obtained, page fetched
                    docID = jedis.incr(REDIS_URL_ID_KEY);
                    try {
                        doc = Jsoup.connect(item).timeout(TIMEOUT).get();
                    } catch (Exception e) {
                        continue;
                    }
                    // store data to sql
                    storeUrlToDatabase(docID, item, dbConnection);
                    // should we stop?
                    int count = processedCount.incrementAndGet();
                    if (count >= TARGET_COUNT) {
                        synchronized (processedCount) {
                            processedCount.notifyAll();
                        }
                        break;
                    }
                    // the thread continue to work...
                    // analyze the page content
                    String[] content = doc.body().text().split("\\s+");

                    //ArrayList<String> stemmedWords = new ArrayList<>(content.length);
                    HashMap<String,Integer> stemmedWords = new HashMap<>(content.length);
                    for (String s : content) {
                        if(!stopwords.contains(s)) {
                            String ss = s.substring(0, Math.min(MAX_STRING_LENGTH,s.length()));
                            String stemmed = jedis.hget(STEMMER_KEY, ss);
                            if (stemmed != null)
                                stemmedWords.put(stemmed,stemmedWords.getOrDefault(stemmed,0)+1);
                            else {
                                String sss = stopStem.stem(ss);
                                stemmedWords.put(sss,stemmedWords.getOrDefault(sss,0)+1);
                                jedis.hsetnx(STEMMER_KEY, ss, sss);
                            }
                        }
                    }

                    // combine and then commit
                    String sql = "INSERT INTO " + INVERTED_INDEX_TABLE + " (word, did, freq) VALUES (?, ?, ?)";
                    try {
                        PreparedStatement pstmt = dbConnection.prepareStatement(sql);
                        dbConnection.setAutoCommit(false);
                        int batchSize = 100;

                        for (Map.Entry<String, Integer> entry : stemmedWords.entrySet()) {
                            pstmt.setString(1, entry.getKey());
                            pstmt.setLong(2, docID);
                            pstmt.setLong(3, entry.getValue());
                            pstmt.addBatch();
                            if (++count % batchSize == 0) {
                                pstmt.executeBatch();
                                dbConnection.commit();
                            }
                        }
                        pstmt.executeBatch();
                        dbConnection.commit();
                        dbConnection.setAutoCommit(true);
                    }catch (SQLException e){
                        System.out.println(e);
                        continue;
                    }

                    // scanning children
                    Elements links = doc.select("a[href]");
                    for (Element link : links) {
                        String nextUrl = link.absUrl("href");
                        if (isValidUrl(nextUrl)) {
                            long added = jedis.sadd(REDIS_VISITED_URLS_KEY, nextUrl);
                            if (added != 0) {
                                queue.add(nextUrl);
                                System.out.println(queue.size());
                            }
                        }
                    }
                }
            }catch (InterruptedException e){
                return;
            } finally {
                jedis.close();
                try {dbConnection.close();} catch (Exception f){;}
                System.out.println(Thread.currentThread().getName() + "closed his jedis&mysql connection properly.");
            }
        }
    }
}
