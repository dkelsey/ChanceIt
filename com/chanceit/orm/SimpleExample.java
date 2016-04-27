package com.chanceit.orm;

import org.javalite.activejdbc.Base;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Date;
import java.io.IOException;
import java.sql.SQLException;
import java.beans.PropertyVetoException;
import com.chanceit.conpool.DataSource;
import com.mchange.v2.c3p0.ComboPooledDataSource;
import com.mchange.v2.c3p0.DataSources;


public class SimpleExample {
    final static Logger logger = LoggerFactory.getLogger(SimpleExample.class);
    public static void main(String[] args) {
        ComboPooledDataSource dataSourcePooled = new ComboPooledDataSource();
        try {

            // the old way below.
            //Base.open("com.mysql.jdbc.Driver", "jdbc:mysql://localhost:3306/chance_it", "rovans", "Jasper!16");

            // the new way:
            dataSourcePooled = DataSource.getInstance().getPool();
            Base.open(dataSourcePooled);

            createGame();
            logger.info("=========> Created game:");
            selectGame();
            updateGame();
            logger.info("=========> Updated game:");
            selectAllGames();
            deleteGame();
            logger.info("=========> Deleted game:");
            selectAllGames();
            createGame();
            logger.info("=========> Created game:");
            selectGame2();
            deleteAllGames();
            logger.info("=========> Deleted all games:");
            selectAllGames();

            createTurn();
            logger.info("=========> Created turn:");
            selectTurn();
            updateTurn();
            logger.info("=========> Updated turn:");

            Base.exec("TRUNCATE TABLE Games");
            logger.info("=========> TRUNCATED table Games:");
            Base.exec("TRUNCATE TABLE Turns");
            logger.info("=========> TRUNCATED table Turns:");
            Base.close(); // connection goes back to the pool

        } catch (IOException e) {}
          catch (SQLException se) {}
          catch (PropertyVetoException se) {}
          finally {
                dataSourcePooled.close();
          }

    }

    private static void createGame() {
        Date date = new Date();

        Game g = new Game();
        g.set("player1", 1);
        g.set("player2", 2);
        g.set("startTime", date);
        g.set("endTime", date);
        g.set("winner", 1);
        g.set("score", 120);
        g.saveIt();
    }

    private static void selectGame() {
        Game g = Game.findFirst("id = ?", 1);
        logger.info(g.toString());
        logger.info(String.format("id: %d", g.getId()));
    }

    private static void selectGame2() {
        Game g = Game.findFirst("id = ?", 2);
         logger.info(g.toString());
        //logger.info(String.format("id: %d", g.get("id")));
    }

    private static void updateGame() {
        Game g = Game.findFirst("id = ?", 1);
        g.set("score", 150).saveIt();
    }

    private static void deleteGame() {
        Game g = Game.findFirst("id = ?", 1);
        g.delete();
    }

    private static void deleteAllGames() {
            Game.deleteAll();
    }

    private static void selectAllGames() {
            logger.info("Games list: " + Game.findAll());
    }

    private static void createTurn() {
        Date date = new Date();

        Turn g = new Turn();
        g.set("gameId", 1);
        g.set("playerId", 2);
        g.set("turnNum", 1);
        g.set("firstRollDie1", 3);
        g.set("firstRollDie2", 1);
        g.set("turnScore", 4);
        g.saveIt();
    }

    private static void selectTurn() {
        Turn t = Turn.findByCompositeKeys(1, 2, 1);
        logger.info(t.toString());
      //  logger.info(String.format("id: %s", g.getId()));
    }

    private static void updateTurn() {
        Turn t = Turn.findByCompositeKeys(1,2,1);
        t.set("turnScore", 150).save();
    }

}
