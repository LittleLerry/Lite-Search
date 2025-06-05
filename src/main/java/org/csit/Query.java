package org.csit;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class Query {
    private final HashSet<String> stopwords = new HashSet<>();
    private final int PAGE_LIMITATION = 256;
    public static final String EOQ = "END_OF_QUERY";
    StopStem stopStem = new StopStem("stopwords.txt");
    private static int threadCount = 3;

    // concurrent hashmap
    // return result MAX to 20
    static class DocFreq {
        final int did;
        final int freq;

        DocFreq(int did, int freq) {
            this.did = did;
            this.freq = freq;
        }
    }

    static class DocScore {
        final int did;
        final double score;
        DocScore(int did, double score) {
            this.did = did;
            this.score = score;
        }
    }
    public Query(){
        try (BufferedReader br = new BufferedReader(new FileReader("/Users/zxzhang/Desktop/lite-search/src/main/java/org/csit/stopwords.txt"))) {
            String line = null;
            while ((line = br.readLine()) != null) {
                stopwords.add(line.trim());
            }
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
    }
    // FOR test ONLY
    public static void main(String[] args){
        Query q = new Query();
        Integer[] a = q.getQueryResult("1979",200,10);
        for(int i=0;i<a.length;i++){
            System.out.println(a[i]+" ");
        }
    }

    public Integer[] getQueryResult(String query, int totalDoc,int limits){
        String[] queries = query.toLowerCase().split("\\b+");
        BlockingQueue<String> wordQueue = new LinkedBlockingQueue<>();
        ConcurrentMap<Integer, Double> resultMap = new ConcurrentHashMap<>();
        for(String q: queries){
            if(!stopwords.contains(q)){
                String qq = q.substring(0,Math.min(BFSTaskManager.MAX_STRING_LENGTH,q.length()));
                wordQueue.add(stopStem.stem(qq));
            }
        }
        wordQueue.add(EOQ);
        // connection lists
        List<Connection> connections = new ArrayList<>();
        try {
            for (int i = 0; i < threadCount; i++) {
                Connection conn = DriverManager.getConnection(
                        BFSTaskManager.DB_URL,
                        BFSTaskManager.DB_USER,
                        BFSTaskManager.DB_PASSWORD);
                connections.add(conn);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            executor.execute(new Worker(totalDoc,wordQueue, resultMap, connections.get(i)));
        }
        // waiting...
        executor.shutdown();
        System.out.println("shutdown");
        try {
            executor.awaitTermination(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // close sql connection
        for (Connection conn : connections) {
            try {
                conn.close();
            } catch (SQLException e) {
                e.printStackTrace();
                continue;
            }
        }
        // O(n) to obtain best result
        PriorityQueue<DocScore> minHeap = new PriorityQueue<>(Comparator.comparingDouble(x -> x.score));
        for (Map.Entry<Integer, Double> entry : resultMap.entrySet()) {
            if(minHeap.size() >= Math.min(PAGE_LIMITATION,limits)){
                minHeap.remove();
            }
            minHeap.add(new DocScore(entry.getKey(),entry.getValue()));
        }

        return minHeap.stream().sorted(Comparator.comparingDouble(x -> -x.score)).map(x -> x.did).filter(x -> x>0).toArray(Integer[]::new);
    }


    static class Worker implements Runnable {
        private final BlockingQueue<String> wordQueue;
        private final ConcurrentMap<Integer, Double> resultMap;
        private final Connection dbConnection;
        private int N;

        public Worker(int totalDoc, BlockingQueue<String> wordQueue, ConcurrentMap<Integer, Double> resultMap, Connection dbConnection) {
            this.wordQueue = wordQueue;
            this.resultMap = resultMap;
            this.dbConnection = dbConnection;
            this.N = totalDoc;
        }
        @Override
        public void run() {
            try {
                while (true) {
                    String word = wordQueue.take();
                    // finished
                    if (word.equals(EOQ)) {
                        wordQueue.put(word);
                        break;
                    }
                    ArrayList<DocFreq> docFreqs = new ArrayList<>();
                    String sql = "SELECT did, freq FROM " + BFSTaskManager.INVERTED_INDEX_TABLE + " WHERE word = ?";

                    try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
                        stmt.setString(1, word);
                        ResultSet rs = stmt.executeQuery();
                        while (rs.next()) {
                            int did = rs.getInt("did");
                            int freq = rs.getInt("freq");
                            docFreqs.add(new DocFreq(did, freq));
                        }
                    }catch (SQLException e){
                        continue;
                    }
                    for (DocFreq docFreq : docFreqs) {
                        Double idf = Math.log(1 + N / (0.0 + docFreqs.size()));
                        // Norm required here
                        Double tf = docFreq.freq + 0.0;
                        // add to result
                        resultMap.merge(docFreq.did, tf*idf, Double::sum);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
