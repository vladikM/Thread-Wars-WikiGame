package rar.java;

import rar.java.repository.*;
import repository.WikiGameAlgoImpl;

public class Main {
    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();
        WikiGame wikiGame = new LoomAlgoImpl();
//        var path = wikiGame.play("Алгебра", "Ятаган", 6);
        var path = wikiGame.play("Бакуган", "Библия", 6);
        System.out.println(path);
        long endTime = System.currentTimeMillis();
        System.out.println("Total execution time: " + (endTime - startTime) + "ms");
    }
}