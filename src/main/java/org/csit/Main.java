package org.csit;

import java.io.IOException;
import java.sql.SQLException;

public class Main {
    public static void main(String[] args){
        boolean skip = true;
        if(!skip){
            String rootUrl = "https://www.cse.ust.hk/~kwtleung/COMP4321/testpage.htm";
            try {
                BFSTaskManager processor = new BFSTaskManager(rootUrl, 100, 3);
                processor.startProcessing();
            }catch (SQLException e){
                System.out.println(e);
            }
        }
    }

}