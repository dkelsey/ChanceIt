package com.chanceit;

import com.chanceit.PlayerRegistrar ;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

class GamePlayWorker extends Thread implements RunnableRecycler {

      // not set from a property
      private int OPPONENT_NOTIFY_DELAY = 1 ; // 1 second delay before notifying each player who their opponent is.
      private static final String WINNER_MESSAGE = "You Win!" ;
      private static final String LOSER_MESSAGE = "You Lose." ;
      private static final String TIE_MESSAGE = "It's a Draw." ;

     /*
      * There is a requirement that players can unregister before they get added to a game.
      *
      * This sets up a problems:  the registration thread will block on IO (after completing registration with the HELLO message) anticipating
      * a GOODBYE message.  The GamePlayWorker, in another thread, can have picked up the registration and added it to a game.  Note: it does
      * so in a synchronized block around the registrationQueue, where it sets gameOn and canUnregister == false.  The GamePlayWorker continues
      * on to giving a turn to each player; they emit a "chance-it?" and wait on IO on the input stream.  Here lies the problem: the registrar
      * thread is already waiting on the same input stream.  The solution was to set a timeout of 1 second in the registrar thread and check
      * after each timeout to see if canUnregister == false-- this will terminate the loop.  In the GamePlayWorker thread, I set a short timeout
      * on the input socket.  The timeouts will be subsequential: first the registrar IO block will timeout, then the GamePlayWorker IO block
      * will.  Following the timout in the GamePlayWorker thread, a check will be performed to see whether input was received from either the
      * registrar thread or from the GamePlayWorker thread.  The SHORT_IO_TIMOUT below is the timeout period of the GamePlayWorker thead.
      */
      private int SHORT_IO_TIMEOUT = 250 ;  // used in ActivePlayer.  The registration thread can be blocked on IO when the game thread
                                          // is also blocked on IO.  This can occur on the first turn of a game.  The registration thread
                                          // will timeout after 1 second, check if  it is now part of a game.  The IO block in the game
                                          // thread will timeout quickly and check whether the registration thread received data or the game
                                          // thread received data. ... complicated, I know.  This is required so that players have the
                                          // ability to unregister before a game starts.

      // these will be overriden in the construcor by valuse from config.properties
      private int NUMBER_OF_TURNS = 2;        // the number of turns that make up a game.
      private int PLAYER_INPUT_TIMEOUT = 10 ; // number of seconds the server waits for input from a player.
      private int ROLL_TIME = 1 ;             // a sumulation of how long a roll takes
      private BufferedWriter gameLog ;

      ConcurrentLinkedQueue<PlayerRegistrar> registrationQueue ;
      Properties prop ;
      boolean gameOn ;
      ActivePlayer currentPlayer ;
      ActivePlayer ap1 ;
      ActivePlayer ap2 ;

      public class Data {
        public int player0Score;
        public int player1Score;
      }

      public Data playerData;

      @SuppressWarnings("serial")
      public class ActivePlayerTimeoutException extends Exception {
          ActivePlayer winner, loser;
          public ActivePlayerTimeoutException(ActivePlayer winner, ActivePlayer loser ) { super(); this.winner = winner; this.loser = loser; }
      }

      @SuppressWarnings("serial")
      public class ActivePlayerDisconnectException extends Exception {
          ActivePlayer winner, loser;
          public ActivePlayerDisconnectException(ActivePlayer winner, ActivePlayer loser ) { super(); this.winner = winner; this.loser = loser; }
      }

      @SuppressWarnings("serial")
      public class ActivePlayerStopException extends Exception {
          ActivePlayer winner, loser;
          public ActivePlayerStopException(ActivePlayer winner, ActivePlayer loser ) { super(); this.winner = winner; this.loser = loser; }
      }

      public interface CallBack {
        void setState(RegistrationWaitState state);
        void setMessage(String message);
      }

      class ActivePlayer {

          Socket socket ;
          PlayerRegistrar pr ;
          String name ;
          ActivePlayer opponent ;
          BufferedReader input ;
          PrintWriter output ;
          Random die ;
          RegistrationWaitState state ;
          String registrarMessage ;

          public ActivePlayer(Socket socket, PlayerRegistrar pr, String name, BufferedReader input, PrintWriter output) {
              this.socket = socket ;   // necessary reference to close the socket when the game is done
              this.pr = pr ;           // necessary reference. needed in CASE were PlayerRegistrar readline blocks on GOODBYE & times out
              this.name = name ;
              this.input = input ;     // references to input and output stream from PlayerRegistrar.
              this.output = output ;
              this.die = new Random(); // this is a die used in the game
          }

          /*
           * Determine if this player goes first with a die roll
           */
          public int rollToSeeWhoGoesFirst() {
            int roll = die.nextInt(6) + 1;
            return roll;
          }

          public void setSocketTimeout(int timeout) {
              try {
                  this.socket.setSoTimeout(timeout);
              } catch (IOException ioe) {}
          }

          private void displayStats(int turnNumber, int rollNumber, int startingScore, int opponentStartScore, int turnAccumulation, int turnScore, int rolledDie1, int rolledDie2){
              output.println(String.format(
                  "Turn#: %d\nRoll#: %d\nTurn Starting Score: %d-%d\nRunning Turn Score: %d\nRoll Score: %d\nYou Rolled: [%d,%d]\n--",
                      turnNumber + 1,
                      rollNumber,
                      startingScore,
                      opponentStartScore,
                      turnAccumulation,
                      turnScore,
                      rolledDie1,
                      rolledDie2
                        ));
          }

          private String getNextCommand() throws IOException, ActivePlayerDisconnectException {

              /*
               * This is tricky as there is a race condition between threads:
               *   PlayerRegistrar thread is blocking waiting for GOODBYE message
               *   This thread wants to block/receive the next command -- Y || '' || n || stop
               */
              String command = null;

              if (state != null && state == RegistrationWaitState.WAIT_TURN){

                  // change here
                  // output.println("chance-it? [Y/n]");
                  command = input.readLine();
                  if (command == null) {
                      // this means the player has terminated or disconnected the socket.
                      // this player forfeits the game.
                      throw new ActivePlayerDisconnectException(/*winner=*/this.opponent, /* loser-*/this);
                  }

              } else if (this.pr != null && this.pr.state == RegistrationWaitState.WAIT_GOODBYE) {
                  // The corresponding registration thread is blocked waiting on IO on the socket in anticipation of a GOODBYE msg.
                  // The readLine will block next after the other readline gets data.
                  // set a short timeout on the socket -- this will only affect the preceding wait on IO.
                  // after this times out...there will be no data (I hope :) ) received from this readline, buto
                  // the other one in the other thread will have data and set pr.message.
                  // get the value of pr.message.
                  String nextCommand = "";
                  // reset the timeout on the socket.
                  int oldTimout = socket.getSoTimeout();
                  socket.setSoTimeout(GamePlayWorker.this.SHORT_IO_TIMEOUT); // set a short timeout. -- may want to put the "chance-it" prompt in this block?
                  try {
                      // change here
                      // output.println("chance-it? [Y/n]");
                      nextCommand = input.readLine();  // could this potentially receive 'chance-it' given I've now set a
                                                       // 1 sec timeout on the socket in the PlayerRegistrar thread ?
                      if (null != nextCommand) {
                          // got a command here
                          command = nextCommand ;
                      }

                  } catch (InterruptedIOException e) {
                    // if we are here the readLine timedout without data.  The PlayerRegistrar thread could have received data so check in the finally
                  } finally {
                        // The other thead has to be unblocked/received data before controll passes to this thread and
                        // runs the input.readLine() and Times out.
                        if (null != this.pr.message) {
                            // got command from the PlayerRegistrar thread
                            command = this.pr.message ;
                        } else if ( null != registrarMessage ){
                            // the callback worked
                            command = registrarMessage ;
                        }
                  }

                  // unset pr to next time around we don't got through this.
                  this.pr = null;

                  // reset the timeout to the old value
                  socket.setSoTimeout(oldTimout);

              }
              return command ;
          }

          public void takeTurn(int turnNumber, Data playerData)  throws ActivePlayerTimeoutException, ActivePlayerDisconnectException, ActivePlayerStopException {
              try {

                  int firstRollDie1 = 0,  // for recording the first roll of each turn
                      firstRollDie2 = 0,

                      rolledDie1 = 0,     // subsequent rolls of each turn
                      rolledDie2 = 0,

                      turnAccumulation = 0,
                      rollNum = -1,
                      startingScore = playerData.player0Score;


                  /*
                  output.println(String.format("Turn: %d\nRoll: %d\nStarting Score: %d-%d\nRunning Score: %d\nTurn Score: %d\nYou Rolled: [%d,%d]\n--",
                                                turnNumber + 1,
                                                rollNum,
                                                startingScore,
                                                //playerData.player0Score + firstRollDie1 + firstRollDie2,
                                                playerData.player1Score,
                                                playerData.player0Score,
                                                turnAccumulation,
                                                firstRollDie1,
                                                firstRollDie2
                                                ));
                                                */

                  while (true) {

                      rollNum++;
                      //output.println("chance-it?");
                      if (0 == rollNum){  // on first roll
                          // take the first roll, show stats and prompt player
                          firstRollDie1 = rolledDie1 = die.nextInt(6) + 1;
                          firstRollDie2 = rolledDie2 = die.nextInt(6) + 1;

                          // accumulate this score
                          turnAccumulation += rolledDie1 + rolledDie2 ;
                          // show stats and prompt player for next moove will occurr in the next loop
/*
//                          // show stats
//                          displayStats(turnNumber + 1, rollNum, startingScore, playerData.player1Score, /* playerData.player0Score,* / turnAccumulation, rolledDie1, rolledDie2) ;
                          /*
                            output.println(String.format("Turn: %d\nRoll: %d\nStarting Score: %d-%d\nRunning Score: %d\nTurn Score: %d\nYou Rolled: [%d,%d]\n--",
                                                      turnNumber + 1,
                                                      rollNum,
                                                      startingScore,
                                                      //playerData.player0Score + firstRollDie1 + firstRollDie2,
                                                      playerData.player1Score,
                                                      playerData.player0Score,
                                                      turnAccumulation,
                                                      firstRollDie1,
                                                      firstRollDie2
                                                      ));
                            */
                      } else {
                           // show stats, prompt for command, grab command,  follow instructions, loop or not

                           // show stats
                           displayStats(turnNumber, rollNum, startingScore, playerData.player1Score, turnAccumulation, rolledDie1 + rolledDie2, rolledDie1, rolledDie2) ;

                           // prompt for command
                           output.println("chance-it? [Y/n]");
                           //output.flush();

                           // get next command
                           String command = getNextCommand() ;

                           // simulate a human roll of the dice
                           Thread.sleep(1000 * 1) ;
                           //Thread.sleep(500 * 1) ;

                           // [Y/n] means pressing enter defaults to 'Y'
                           if (command.length() == 0 ||
                               command.startsWith("Y") ||
                               command.startsWith("chance-it")) {

                               // taking the roll 'Y or [enter]' count as continue
                               // take the next roll
                               rolledDie1 = die.nextInt(6) + 1;
                               rolledDie2 = die.nextInt(6) + 1;

                               // test if same as initial roll
                               if ( firstRollDie1 + firstRollDie2 == rolledDie1 + rolledDie2) {
                                   // rolled the same as first roll so this turn gets 0
                                   turnAccumulation = 0 ;
                                   // show stats
                                   displayStats(turnNumber, rollNum, startingScore, playerData.player1Score, turnAccumulation, rolledDie1 + rolledDie2, rolledDie1, rolledDie2) ;

                                   // break out of the turn loop
                                   break;
                               } else {
                                   // did not roll the same score as the first so this gets added.
                                   turnAccumulation += rolledDie1 + rolledDie2 ;
                                   // show stats
                                   // loop back
                                   // displayStats(turnNumber + 1, rollNum, startingScore, playerData.player1Score, /* playerData.player0Score,*/ turnAccumulation, rolledDie1, rolledDie2) ;

                               }
                           // 'n' means stop the turn
                           } else if (command.startsWith("n")) {
                             // stopping the turn
                             break ;
                           // 'stop' now means quit the game
                           } else if (command.startsWith("stop")) {
                               // stopping the game
                               throw new ActivePlayerStopException(opponent, this) ;
                           }
                      }
                    } // end while loop

                    playerData.player0Score += turnAccumulation ;

                } catch (InterruptedIOException iioe) {
                    output.println("Other player wins");
                    // re-throw the exception and catch it in the loop to end the game.
                    // include a reference to the other player -- the winner.
                    throw new ActivePlayerTimeoutException(/*winner=*/this.opponent, /* loser-*/this);
                } catch (SocketException se) {
                    // found this in testing when my driver script had fewer turns configured than were set for the game.
                    throw new ActivePlayerDisconnectException(/*winner=*/this.opponent, /* loser-*/this);
                } catch (InterruptedException e) {
                    // This should never occur
                    e.printStackTrace() ;
                } catch (IOException e) {
                    // possabley thrown from getNextCommand()
                }
            } // end takeTurn()

/*
                  try {
                         String command = getNextCommand() ;
*/
/*
                          String command = "";

                          if (state != null && state == RegistrationWaitState.WAIT_TURN){

                              // change here
                              output.println("chance-it?");
                              command = input.readLine();
                              if (command == null) {
                                  // this means the player has terminated or disconnected the socket.
                                  // this player forfeits the game.
                                  throw new ActivePlayerDisconnectException(/*winner=* /this.opponent, /* loser-* /this);
                              }

                          } else if (this.pr != null && this.pr.state == RegistrationWaitState.WAIT_GOODBYE) {
                              // The corresponding registration thread is blocked waiting on IO on the socket in anticipation of a GOODBYE msg.
                              // The readLine will block next after the other readline gets data.
                              // set a short timeout on the socket -- this will only affect the preceding wait on IO.
                              // after this times out...there will be no data (I hope :) ) received from this readline, buto
                              // the other one in the other thread will have data and set pr.message.
                              // get the value of pr.message.
                              String nextCommand = "";
                              // reset the timeout on the socket.
                              int oldTimout = socket.getSoTimeout();
                              socket.setSoTimeout(GamePlayWorker.this.SHORT_IO_TIMEOUT); // set a short timeout. -- may want to put the "chance-it" prompt in this block?
                              try {
                                  // change here
                                  output.println("chance-it?");
                                  nextCommand = input.readLine();  // could this potentially receive 'chance-it' given I've now set a
                                                                   // 1 sec timeout on the socket in the PlayerRegistrar thread ?
                                  if (null != nextCommand) {
                                      // got a command here
                      //                System.out.println( name + ": GOT INPUT FROM SHORT TIMOUT: " + nextCommand) ;
                                      command = nextCommand ;
                                  }

                              } catch (InterruptedIOException e) {
                      //            System.out.println(name + ": !!! The socket has been unblocked  !!!") ;
                      //            System.out.println(String.format("The Read returns: %s", nextCommand));
                              } finally {
                                    // The other thead has to be unblocked/received data before controll passes to this thread and
                                    // runs the input.readLine() and Times out.
                                    if (null != this.pr.message) {
                                        // got command from the PlayerRegistrar thread
                                        command = this.pr.message ;
                      //                  System.out.println(name + ": GOT INPUT FROM PlayerRegistrar: " + command) ;
                                    } else if ( null != registrarMessage ){
                                        // the callback worked
                                        command = registrarMessage ;
                      //                  System.out.println(name + ": :" + Thread.currentThread().getName() + ": GOT INPUT FROM PlayerRegistrar message callback: " + command) ;
                                    }
                              }

                              // unset pr to next time around we don't got through this.
                              this.pr = null;

                              // reset the timeout to the old value
                              socket.setSoTimeout(oldTimout);

                          }
*/

/*
                    //      Thread.sleep(1000 * ROLL_TIME);  // what to do here?
                          Thread.sleep(500 * ROLL_TIME);  // what to do here?
                      //   // System.out.println(name + ": :" + Thread.currentThread().getName() + ": FROM PlayerRegistrar message callback: " + command) ;
                          if (command.startsWith("chance-it")) {
                      //        System.out.println(name + ": :" + Thread.currentThread().getName() + ": IN IF statement FROM PlayerRegistrar message callback: " + command) ;
                              // continue
/*
                              nextRollDie1 = die.nextInt(6) +1;
                              nextRollDie2 = die.nextInt(6) +1;
                              if ( nextRollDie1 + nextRollDie2 == firstRollDie1 + firstRollDie2 ) {
                                  // this turn gets a 0 score
                                  turnAccumulation = 0;
                                  output.println(String.format("Turn: %d\nRoll: %d\nStarting Score: %d-%d\nRunning Score: %d\nTurn Score: %d\nYou rolled: [%d,%d]\n--",
                                                                turnNumber + 1,
                                                                rollNum,
                                                                startingScore,
                                                                //playerData.player0Score + firstRollDie1 + firstRollDie2,
                                                                playerData.player1Score,
                                                                playerData.player0Score,
                                                                turnAccumulation,
                                                                nextRollDie1,
                                                                nextRollDie2));
                                  break;
                              } else {
                                  // this turn gets the score added to turnAccumulation
                                  turnAccumulation += nextRollDie1 + nextRollDie2;
                                  /*
                                  output.println(String.format("Turn: %d\nRoll: %d\nStarting Score: %d-%d\nRunning Score: %d\nTurn Score: %d\nYou rolled: [%d,%d]\n--",
                                                                turnNumber + 1,
                                                                rollNum,
                                                                startingScore,
                                                                //playerData.player0Score + firstRollDie1 + firstRollDie2,
                                                                playerData.player1Score,
                                                                playerData.player0Score,
                                                                turnAccumulation,
                                                                nextRollDie1,
                                                                nextRollDie2));
                                                                * /
                              }
* /
                          } else if (command.startsWith("stop")) {
                              // this turn gets the score added to turnAccumulation
                              //TODO the stop command will now end the game.
                              break ;
                          } else {
                              //TODO this is from a race condition that needs addressing.
                              System.out.println(name + ": :" + Thread.currentThread().getName() + ": WHY ARE WE HERE?  FROM PlayerRegistrar message callback: " + command) ;
                              System.out.println("State is: " + state);
                              System.out.println(Thread.currentThread().getName() + ": :" + name + ": :" + "registrarMessage is: " + registrarMessage) ;
                              // either the message was received by the other thread and we missed it or the timeout was too fast
                              // and the message was yet to be sent....I'm guessing it was the latter so setting state here so we get
                              // another go at readLine on the input stream.
                              // try setting state to WAIT_TURN -- yeash
                              state = RegistrationWaitState.WAIT_TURN ;
                          }
                      } catch (InterruptedIOException iioe) {
                          output.println("Other player wins");
                          // re-throw the exception and catch it in the loop to end the game.
                          // include a reference to the other player -- the winner.
                          throw new ActivePlayerTimeoutException(/*winner=* /this.opponent, /* loser-* /this);
                      } catch (SocketException se) {
                          // found this in testing when my driver script had fewer turns configured than were set for the game.
                          throw new ActivePlayerDisconnectException(/*winner= * /this.opponent, /* loser-* /this);
                      }

                      playerData.player0Score += turnAccumulation ;
                  }
                  /*
                  output.println(String.format("Turn: %d\nRoll: %d\nStarting Score: %d-%d\nRunning Score: %d\nTurn Score: %d\nYou rolled: [%d,%d]\n--",
                                                turnNumber + 1,
                                                rollNum,
                                                startingScore,
                                                //playerData.player0Score + firstRollDie1 + firstRollDie2,
                                                playerData.player1Score,
                                                playerData.player0Score,
                                                turnAccumulation,
                                                nextRollDie1,
                                                nextRollDie2));
                                                * /
              } catch(InterruptedException ex) {
              //    Thread.currentThread().interrupt();
              //    System.out.println("InterruptedException thrown: " + ex);
                  ex.printStackTrace();
              } catch(IOException e) {
              //    System.out.println("GamePlayWorker...player died: " + e);
                  e.printStackTrace() ;
              }
          }
          */

          public void setOpponent(ActivePlayer opponent){
            this.opponent = opponent;
          }
      }

      public GamePlayWorker(ConcurrentLinkedQueue<PlayerRegistrar> registrationQueue, Properties prop, BufferedWriter gameLog) {
          this.registrationQueue = registrationQueue ;
          this.prop = prop ;
          this.gameLog = gameLog ;
          this.gameOn = false ;
          this.NUMBER_OF_TURNS = Integer.parseInt(prop.getProperty("number_of_turns", "2")) ;
          this.PLAYER_INPUT_TIMEOUT = Integer.parseInt(prop.getProperty("player_input_timeout", "10")) ;
          this.ROLL_TIME = Integer.parseInt(prop.getProperty("roll_time", "1")) ;
          this.playerData = new Data() ;
      }

     /*
      *  GamePlayWorker objects are re-used in the ExecutorService therefore certain data members need to be reset.
      */
      @Override
      public void resetData() {

          this.gameOn = false ;
          this.currentPlayer = null;
          this.ap1 = null;
          this.ap2 = null;
          this.playerData = new Data() ;

      }

      @Override
      public void run() {

          while (gameOn == false) {  // the queue could have been unblocked and less that 2 players are in it so continue looping

              try {

                  synchronized (registrationQueue) {
                      registrationQueue.wait() ;
                      if (registrationQueue.size() > 1) {
                        PlayerRegistrar pr1 = registrationQueue.poll();
                        PlayerRegistrar pr2 = registrationQueue.poll();
                        pr1.canUnregister = false;
                        pr2.canUnregister = false;

                        ap1 = new ActivePlayer(pr1.socket, pr1, pr1.name, pr1.input, pr1.output) ;
                        pr1.callback = new CallBack() {
                          public void setState(RegistrationWaitState s) { ap1.state = s; System.out.println(Thread.currentThread().getName() + ": :" + ap1.name + ": REGISTER STATE FROM PR1"); }
                          public void setMessage(String s) { ap1.registrarMessage = s; System.out.println(Thread.currentThread().getName() + ":  :" + ap1.name + ": REGISTER MESSAGE FROM PR1"); }
                        } ;

                        ap2 = new ActivePlayer(pr2.socket, pr2, pr2.name, pr2.input, pr2.output) ;
                        pr2.callback = new CallBack() {
                          public void setState(RegistrationWaitState s) { ap2.state = s ; System.out.println(Thread.currentThread().getName() + ": :" + ap2.name + ": REGISTER STATE FROM PR2"); }
                          public void setMessage(String s) { ap2.registrarMessage = s; System.out.println(Thread.currentThread().getName() + ": :" + ap2.name + ": REGISTER MESSAGE FROM PR2"); }
                        } ;

                        ap1.setOpponent(ap2);
                        ap2.setOpponent(ap1);
                        currentPlayer = ap1;
                        gameOn = true ;
                      }
                      if (registrationQueue.size() > 1) {
                          registrationQueue.notify();
                      }
                }
            } catch (InterruptedException e) {}
        }
        // we've broken out of the loop and this Game is now ON!!
        playGame() ;
        // System.out.println(Thread.currentThread().getName() + " From GamePlayWorker has finished");
      }

      public void playGame() {

           // To verify that getting players out of the queue is working correctly:
           //    • sleep for 1 second
           //    • notify the first player it has Won and the second that it has Lost.
           //    • close the sockets each player has open.
           if (false) {
               try {
                 Thread.sleep(1000 * ROLL_TIME);      // sleep 1 second
                 ap1.output.println(WINNER_MESSAGE);      // notify player 1 they won
                 System.out.println(String.format("     > Player %s WON!!!", ap1.name));
                 ap2.output.println(LOSER_MESSAGE);     // notify player 2 they lost
                 System.out.println(String.format("     > Player %s Lost!!!", ap2.name));
               } catch (InterruptedException e) {}
                 finally {
                   // close these sockets.
                   try { ap1.socket.close(); } catch (IOException e) {}
                   try { ap2.socket.close(); } catch (IOException e) {}
               }
           } else {

               // Notify both players:
               //    * two players are registerred and
               //    * the game is starting.
               notifyNameOfOpponent();

                // Determine which player goes first.
               currentPlayer = getWhoGoesFirst();

               // set socket timeouts
               ap1.setSocketTimeout(1000 * PLAYER_INPUT_TIMEOUT);
               ap2.setSocketTimeout(1000 * PLAYER_INPUT_TIMEOUT);

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

                       } catch (GamePlayWorker.ActivePlayerTimeoutException apte) {
                           // currentPlayer player timed out so they loose the game
                           logGameOutcome(apte.winner.name, playerData.player1Score, apte.loser.name, playerData.player0Score, "TIMEOUT") ;
                           apte.winner.output.println(WINNER_MESSAGE);
                           return ;
                       } catch (GamePlayWorker.ActivePlayerDisconnectException apde) {
                           // currentPlayer player Disconnected out so they loose the game
                           logGameOutcome(apde.winner.name, playerData.player1Score, apde.loser.name, playerData.player0Score, "DISCONNECT") ;
                           apde.winner.output.println(WINNER_MESSAGE);
                           return ;
                       } catch (GamePlayWorker.ActivePlayerStopException apse) {
                           // currentPlayer player Disconnected out so they loose the game
                           logGameOutcome(apse.winner.name, playerData.player1Score, apse.loser.name, playerData.player0Score, "STOP") ;
                           apse.winner.output.println(WINNER_MESSAGE);
                           return ;
                       }
                       try {
                           currentPlayer.takeTurn(i, playerData);
                           currentPlayer = currentPlayer.opponent;

                           // swap the scores
                           int n = playerData.player0Score;
                           playerData.player0Score = playerData.player1Score;
                           playerData.player1Score = n;

                       } catch (GamePlayWorker.ActivePlayerTimeoutException apte) {
                           // currentPlayer player timed out so they loose the game
                           logGameOutcome(apte.winner.name, playerData.player1Score, apte.loser.name, playerData.player0Score, "TIMEOUT") ;
                           apte.winner.output.println(WINNER_MESSAGE);
                           return ;
                       } catch (GamePlayWorker.ActivePlayerDisconnectException apde) {
                           // currentPlayer player Disconnected out so they loose the game
                           logGameOutcome(apde.winner.name, playerData.player1Score, apde.loser.name, playerData.player0Score, "DISCONNECT") ;
                           apde.winner.output.println(WINNER_MESSAGE);
                           return ;
                       } catch (GamePlayWorker.ActivePlayerStopException apse) {
                           // currentPlayer player Disconnected out so they loose the game
                           logGameOutcome(apse.winner.name, playerData.player1Score, apse.loser.name, playerData.player0Score, "STOP") ;
                           apse.winner.output.println(WINNER_MESSAGE);
                           return ;
                       }
                   }

                   // notifiy the winner and the loser
                   if (playerData.player0Score > playerData.player1Score) {
                       logGameOutcome(currentPlayer.name, playerData.player0Score, currentPlayer.opponent.name, playerData.player1Score, "FAIR_PLAY") ;
                       currentPlayer.output.println(WINNER_MESSAGE);
                       currentPlayer.opponent.output.println(LOSER_MESSAGE);
                   } else if (playerData.player0Score < playerData.player1Score) {
                       logGameOutcome(currentPlayer.opponent.name, playerData.player1Score, currentPlayer.name, playerData.player0Score, "FAIR_PLAY") ;
                       currentPlayer.opponent.output.println(WINNER_MESSAGE);
                       currentPlayer.output.println(LOSER_MESSAGE);
                   } else { // it's a draw
                       logGameOutcome(currentPlayer.opponent.name, playerData.player1Score, currentPlayer.name, playerData.player0Score, "FAIR_PLAY||TIE") ;
                       currentPlayer.opponent.output.println(TIE_MESSAGE);
                       currentPlayer.output.println(TIE_MESSAGE);
                   }

               } finally {
          //         System.out.println(String.format("Players %s and %s have finished their game", currentPlayer.name, currentPlayer.opponent.name));
                   // close each players open socket.
                   try { ap1.socket.close();} catch (IOException e) {}
                   try { ap2.socket.close();} catch (IOException e) {}
               }
           }
      }

      public void logGameOutcome(String winnerName, int winnerScore, String loserName, int loserScore, String how) {
          if (null != gameLog) {
              synchronized (gameLog) {
                  try {

                      gameLog.write(String.format("WINNER:%s,score:%s,LOSER:%s,score:%s,HOW:%s\n",
                                  /* winner => */  winnerName, winnerScore,
                                  /* loser => */  loserName, loserScore,
                                                 how));
                      gameLog.flush() ;  // leaving this uncomented for now.
                  } catch (IOException e) {}
              }
          }
      }

      public void notifyNameOfOpponent() {

          // let each player know that another player has registerred
          try {

              Thread.sleep(1000 * OPPONENT_NOTIFY_DELAY); // arbitrary delay
              ap1.output.println("Opponent: " + ap2.name);
              ap2.output.println("Opponent: " + ap1.name);

          } catch (InterruptedException ie) {}

      }

      public ActivePlayer getWhoGoesFirst() {

          ActivePlayer whoGoesFirst = null ;
          int player1Roll,
              player2Roll ;

          while (null == whoGoesFirst) {

              player1Roll = ap1.rollToSeeWhoGoesFirst();
              player2Roll = ap2.rollToSeeWhoGoesFirst();

              if (player1Roll > player2Roll) {
                  whoGoesFirst = ap1;
                  ap1.output.println("Your roll was: " + player1Roll + ". Opponent roll was: " + player2Roll + ". You go first.");
                  ap2.output.println("Your roll was: " + player2Roll + ". Opponent roll was: " + player1Roll + ". " + ap1.name + " goes first.");
              } else if (player1Roll < player2Roll) {
                  whoGoesFirst = ap2;
                  ap1.output.println("Your roll was: " + player1Roll + ". Opponent roll was: " + player2Roll + ". " + ap2.name + " goes first.");
                  ap2.output.println("Your roll was: " + player2Roll + ". Opponent roll was: " + player1Roll + ". You go first.");
              }
          }

          return whoGoesFirst;
      }
      // THE Game LOGIC
      // instantiate a pool of GameWorker Threads.
      // each GameWorker will block on the registrationQueue.
      // when notified on the queue
      //   get the first two players.
      //   mark them as canUnregister == false && unblock them causing them to fall off the end of the tread and be reclaimed by the PlayerPool
      //     NOTE: another reference to the socket will be created therefore
      //           the Player.run() should not close the socket -- this will be done in the ActivePlayer object when the game completes.
      //           unless the player unregisterres then the player should close the socket
      //   add them to a game
      //   run() the game

      // run the game
      //   unblock the two game threads - so the socket is unblocked from waiting for a "GOODBYE:PLAYER NAME" or blocking on the queue
      //   choose which player goes first.
      //   alternate taking turns until the end.
      //     record each turn.
      //   record the winner
      //   close each player socket -- so the GC can reclaim it.
      //  IDEA copy the socket and name into a registerredPlayer or ActivePlayer -- that has a take turn method.

      // PlayerRegistrar pool -- a pool of threads that listens for player connections, registrations and unregistrations
      //    after each registration the object will look to see if the queue has more than 1 registrant and notifies queue watchers.
      //    will the registrar register an ActivePlayer? NO only PlayerRegistrations will be registerred -- added to the queue.
      // GamePlayer pool -- a pool of threads that block on the registrationQueue.
      //    ONE (not all) will be notified by a playerRegistrar when there are more than 1 PlayerRegistrations in the queue.
      //    It will subsequently block again on the registerQueue. ?? what is the synchronized object?
      //    Details:
      //    • It will attempt pop 2 PlayerRegistrations out of the queue.
      //       block on the queue { ??
      //           if queue has 2 players:
      //              2 will be pulled out.
      //              they will be marked as canUnregister = false.
      //              ActivePlayer objects will be instantiated from the PlayerRegistration's socket and name.
      //              gameOn = true
      //              if 2 more players in the queue the notify() another watcher of the queue
      //           else # do nothing
      //       }
      //       if gameOn == true:
      //          PlayerRegistration theads will be unblocked causing them to fall of the end of the thread and be reclaimed
      //          Q/ How do we know what state the PlayerRegistration objects are in?  blocking on the socket or blocking on the queue?
      //
      //          -- IDEA: have another state variable to indicate the PlayerRegistration's state (WAIT_HELLO, WAIT_GOODBYE, WAIT_QUEUE)
      //
      //         if they are blocking on the socket waiting for "GOODBYE" handle that case.
      //                 WAIT_GOODBYE == true: simply interrupt the thread .. the thread must handle this correctly
      //                 no need to do anything other than fall off the end of the thread.
      //         if they are blocking on the queue as they have already receivd a "GOODBYE" handle that calse ... complicated?
      //                 WAIT_QUEUE == true finish block on the queue will continue the player thread...the logic will handle this correctly.
      //                 in the PlayerRegistration object, canUnregister will have been set so check it and fall off the end
      //         if they are blockin on hello : WAIT_HELLO - the game play logic will never see this as they player will not have been registerred
      //                 in the queue yet. -- could be used if re-registration on the same socket is allowed.  meh, not part of the spec.
      //          RACE CONDITION • GamePlayer thread blocks on the queue as described above.  if one of the first two player threads receives an
      //                         unregister message they will block on the queue to remove themselves.  When the player thead is unblocked,
      //                         a game has started etc, the player
      //                         will not exist in the queue and therefor can not un-register...what about checking canUnregister before checking
      //                         the queue? YES.  note: at some point the player can no longer unregister:  unregistering is not a guarantee that they will be
      //                         un-registerred.
      //                         • If a PlayerRegistration thread has blocked (locked) the queue because it is unregesterring itself, then
      //                         when the GamePlayer is unblocked the queue will no longer contain that player.
      //                         All is well.
      //
      //          # the ActivePlayers will have the takeTurn logic to play the game.
      //          # Here the game is played.
      //          # When the game is finished, the sockets will be closed -- they will also be closed if there are any timeouts or errors.
      //       after this the GamePlayer thread will be finished and can return to the pool.
      //    the next thread (and all subsequent threads) will attempt to pop 2 PlayerRegistrations out of the queue and do the same as above.
      //
      //    Notifying all watchers on the queue, will that cause a stampeeding herd?
      //    notifyAll() would wake up all threads.  notify() wakes up a single waiting thread.
      //    what if there are more that 1 queued playersregistrations?
      //      After the game has popped 2 off then they game should re-notify() another waiting thread!
}
