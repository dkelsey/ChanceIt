package com.chanceit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

/**
 * A server for a network multi-player tic tac toe game.  Modified and
 * extended from the class presented in Deitel and Deitel "Java How to
 * Program" book.  I made a bunch of enhancements and rewrote large sections
 * of the code.  The main change is instead of passing *data* between the
 * client and server, I made a TTTP (tic tac toe protocol) which is totally
 * plain text, so you can test the game with Telnet (always a good idea.)
 * The strings that are sent in TTTP are:
 *
 *  Client -> Server           Server -> Client
 *  ----------------           ----------------
 *  MOVE <n>  (0 <= n <= 8)    WELCOME <char>  (char in {X, O})
 *  QUIT                       VALID_MOVE
 *                             OTHER_PLAYER_MOVED <n>
 *                             VICTORY
 *                             DEFEAT
 *                             TIE
 *                             MESSAGE <text>
 *
 * A second change is that it allows an unlimited number of pairs of
 * players to play.
 */
public class ChanceItServer {
    private static final int SERVER_PORT = 1099 ;
    /**
     * Runs the application. Pairs up clients that connect.
     */
    public static void main(String[] args) throws Exception {
        ServerSocket listener = new ServerSocket(SERVER_PORT);
        System.out.println("Chance-It Server is Running");
        try {
            while (true) {
                Game game = new Game();

                // CountDownLatch is used to synchronized the two player threads.
                // continueSignal.countDown() called in each player thread after team registerres their name
                CountDownLatch continueSignal = new CountDownLatch(2);

                Game.Player playerOne = game.new Player(listener, continueSignal);
                playerOne.start();

                Game.Player playerTwo = game.new Player(listener, continueSignal);
                playerTwo.start();

                playerOne.setOpponent(playerTwo);
                playerTwo.setOpponent(playerOne);

                // This is arbitrarry as the order of which player takes the first turn is determines after a die roll.
                game.currentPlayer = playerOne;

                continueSignal.await();         // Wait until each player has registerred themselves.
                System.out.println(String.format("Players: %s and %s have successfully registerred", playerOne.name, playerTwo.name));
                System.out.println("Their Game is about to start");

                // begin a game
                game.start();
            }
        } finally {
            listener.close();
        }
    }
}

/**
 * A two-player game.
 */
class Game extends Thread {

    private final int NUMBER_OF_TURNS = 2;
    private final int PLAYER_INPUT_TIMOUT = 10 ; // number of seconds the server waits for input from a player
    /**
     * The current player.
     */
    Player currentPlayer;

    public class Data {
      public int player0Score;
      public int player1Score;
    }

    public Data playerData = new Data();

    /**
     * The run method of this thread.
     */
    @Override
    public void run() {

        System.out.println("Game Thread has started.");

        // Notify both players:
        //    * two players are registerred and
        //    * the game is starting.
        notifyNameOfOponent();

         // Determine which player goes first.
        currentPlayer = getWhoGoesFirst();

        // set socket timeouts
        currentPlayer.setSocketTimeout(1000 * PLAYER_INPUT_TIMOUT);
        currentPlayer.opponent.setSocketTimeout(1000 * PLAYER_INPUT_TIMOUT);

        try {
            // the game turn loop
            for(int i=0; i<NUMBER_OF_TURNS; i++)
            {
                try {
                    currentPlayer.takeTurn(i, playerData);
                    currentPlayer = currentPlayer.opponent;

                    // swap the scores
                    int n = playerData.player0Score;
                    playerData.player0Score = playerData.player1Score;
                    playerData.player1Score = n;

                } catch (Game.Player.PlayerTimeoutException pte) {
                    // currentPlayer player timed out so they loose the game
                    System.out.println(String.format("Player: %s won, Player: %s lost", pte.winner.name, pte.looser.name));
                    pte.winner.output.println("you won!");
                    return ;
                }
                try {
                    currentPlayer.takeTurn(i, playerData);
                    currentPlayer = currentPlayer.opponent;

                    // swap the scores
                    int n = playerData.player0Score;
                    playerData.player0Score = playerData.player1Score;
                    playerData.player1Score = n;

                } catch (Game.Player.PlayerTimeoutException pte) {
                    // currentPlayer player timed out so they loose the game
                    System.out.println(String.format("Player: %s won, Player: %s lost", pte.winner.name, pte.looser.name));
                    pte.winner.output.println("you won!");
                    return ;
                }
            }

            // notifiy the winner and the looser
            if (playerData.player0Score > playerData.player1Score) {
                System.out.println(String.format("WINNER => Player: %s, score: %s", currentPlayer.name, playerData.player0Score));
                System.out.println(String.format("LOOSER => Player: %s, score: %s", currentPlayer.opponent.name, playerData.player1Score));
                currentPlayer.output.println("You Won!");
                currentPlayer.opponent.output.println("You Lost!");
            } else {
                System.out.println(String.format("WINNER => Player: %s, score: %s", currentPlayer.opponent.name, playerData.player1Score));
                System.out.println(String.format("LOOSER => Player: %s, score: %s", currentPlayer.name, playerData.player0Score));
                currentPlayer.opponent.output.println("You Won!");
                currentPlayer.output.println("You Lost!");
            }

        } finally {
            System.out.println(String.format("Players %s and %s have finished their game", currentPlayer.name, currentPlayer.opponent.name));
            // close each players open socket.
            try { currentPlayer.socket.close();} catch (IOException e) {}
            try { currentPlayer.opponent.socket.close();} catch (IOException e) {}
        }
    }

    public void notifyNameOfOponent() {

        // let each player know that another player has registerred
        try {
            Thread.sleep(1000); // arbitrarry delay
            currentPlayer.output.println(String.format("Opponent: %s", currentPlayer.opponent.name));
            currentPlayer.opponent.output.println(String.format("Opponent: %s", currentPlayer.name));
        } catch (InterruptedException ie) {}

    }

    public Player getWhoGoesFirst() {

        Player whoGoesFirst ;
        int firstRoll = currentPlayer.rollToSeeWhoGoesFirst();
        int secondRoll = currentPlayer.opponent.rollToSeeWhoGoesFirst();

        if (firstRoll > secondRoll) {
            whoGoesFirst = currentPlayer;
        } else if (firstRoll < secondRoll) {
            whoGoesFirst = currentPlayer.opponent;
        } else {
            whoGoesFirst = getWhoGoesFirst();  // 'little recurrsion never hurt no body
        }

        return whoGoesFirst;
    }

    /**
     * The class for the helper threads in this multithreaded server
     * application.  A Player is identified by a character mark
     * which is either 'X' or 'O'.  For communication with the
     * client the player has a socket with its input and output
     * streams.  Since only text is being communicated we use a
     * reader and a writer.
     */
    class Player extends Thread {
        String name;
        Player opponent;
        ServerSocket serverSocket;
        Socket socket;
        BufferedReader input;
        PrintWriter output;
        Random die;
        CountDownLatch continueSignal;

        /**
         * Constructs a handler thread for a given socket and mark
         * initializes the stream fields, displays the first two
         * welcoming messages.
         */
        public Player(ServerSocket serverSocket, CountDownLatch continueSignal) {
            this.serverSocket = serverSocket;
            this.continueSignal = continueSignal;
        }

        public void setSocketTimeout(int timeout) {
            try {
                this.socket.setSoTimeout(timeout);
            } catch (IOException ioe) {}
        }

        @Override
        public void run() {

            try {

                socket = serverSocket.accept();
                input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                output = new PrintWriter(socket.getOutputStream(), true);
                die = new Random();

            } catch (IOException e) {
                System.out.println("Player died: " + e);
            }
            /*TODO implement HELLO protocol
            *
            * CLIENT                            SERVER
            *    -----------connect--------------->
            *    ---HELLO:MCLOVIN ---------------->
            *    <--IS IT ME YOUR LOOKIN FOR?...---
            */
            try {
                String command = input.readLine();
                if (command.startsWith("HELLO:") && command.length() > "HELLO:".length()){
                      this.name = command.substring("HELLO:".length(), command.length() );
                }
                output.println("IS IT ME YOU'RE LOOKIN FOR?");
                System.out.println(name);
            } catch (IOException ioe) {}
            output.println("WELCOME " + name);
            continueSignal.countDown();
        }
        /**
         * Accepts notification of who the opponent is.
         */
        public void setOpponent(Player opponent) {
            this.opponent = opponent;
        }

        /*
         * Determine if this player goes first with a die roll
         */
        public int rollToSeeWhoGoesFirst() {
          int roll = die.nextInt(6) + 1;
          output.println("Your roll was: " + roll);
          return roll;
        }
        /**
         * The player takes their turn
         */
        public void takeTurn(int turnNumber, Data playerData)  throws PlayerTimeoutException
        {
            try {
                // first roll for this turn
                int firstRollDie1 = die.nextInt(6) + 1,
                    firstRollDie2 = die.nextInt(6) + 1,

                    nextRollDie1,   // subsequent rolls
                    nextRollDie2,

                    turnAccumulation = firstRollDie1 + firstRollDie2,
                    rollNum = 1;

                output.println(String.format("Turn: %d\nRoll: %d\nScore: %d-%d\nTurn Score: %d",
                                              turnNumber + 1,
                                              rollNum,
                                              playerData.player0Score,
                                              //playerData.player0Score + firstRollDie1 + firstRollDie2,
                                              playerData.player1Score,
                                              turnAccumulation));

                while (true) {

                    rollNum++;
                    output.println("chance-it?");
                    try {
                        String command = input.readLine();
                        Thread.sleep(1000);
                        if (command.startsWith("chance-it")) {
                            nextRollDie1 = die.nextInt(6) +1;
                            nextRollDie2 = die.nextInt(6) +1;
                            if ( nextRollDie1 + nextRollDie2 == firstRollDie1 + firstRollDie2 ) {
                                // this turn gets a 0 score
                                turnAccumulation = 0;
                                output.println(String.format("Turn: %d\nRoll: %d\nScore: %d-%d\nTurn Score: %d",
                                                              turnNumber + 1,
                                                              rollNum,
                                                              playerData.player0Score,
                                                              //playerData.player0Score + firstRollDie1 + firstRollDie2,
                                                              playerData.player1Score,
                                                              turnAccumulation));
                                break;
                            } else {
                                // this turn gets the score added to turnAccumulation
                                turnAccumulation += nextRollDie1 + nextRollDie2;
                                output.println(String.format("Turn: %d\nRoll: %d\nScore: %d-%d\nTurn Score: %d",
                                                              turnNumber + 1,
                                                              rollNum,
                                                              playerData.player0Score,
                                                              //playerData.player0Score + firstRollDie1 + firstRollDie2,
                                                              playerData.player1Score,
                                                              turnAccumulation));
                            }
                        } else if (command.startsWith("stop")) {
                            // this turn gets the score added to turnAccumulation
                            break;
                        }
                    } catch (InterruptedIOException iioe) {
                        output.println("Other player wins");
                        // re-throw the exception and catch it in the loop to end the game.
                        // include a reference to the other player -- the winner.
                        throw new PlayerTimeoutException(/*winner=*/this.opponent, /* looser-*/this);
                    }
                    playerData.player0Score += turnAccumulation ;
                }
            } catch(InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch(IOException e) {
                System.out.println("player died: " + e);
            }
        }

        @SuppressWarnings("serial")
        public class PlayerTimeoutException extends Exception {
          Player winner, looser;
          public PlayerTimeoutException(Player winner, Player looser ) { super(); this.winner = winner; this.looser = looser; }
        }
    }
}
