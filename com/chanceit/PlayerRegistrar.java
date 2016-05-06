package com.chanceit ;

import java.io.BufferedReader ;
import java.io.IOException ;
import java.io.IOException;
import java.io.InputStreamReader ;
import java.io.InterruptedIOException;
import java.io.PrintWriter ;
import java.net.ServerSocket ;
import java.net.Socket ;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue ;

class PlayerRegistrar extends Thread implements RunnableRecycler {

  private static int K_MILLISECS        = 1000 ;
  private int GOODBYE_LOOP_TIMEOUT      = K_MILLISECS * 1; // one second
  private String HELLO_REQUEST_MESSAGE  = "HELLO:" ;
  private String HELLO_RESPONSE_MESSAGE = "IS IT ME YOU'RE LOOKIN FOR?" ;
  private String GOODBY_REQUEST_MESSAGE = "GOODBYE:" ;

  ServerSocket serverSocket;
  ConcurrentLinkedQueue<PlayerRegistrar> registrationQueue;
  Properties prop ;
  Socket socket;
  BufferedReader input;
  PrintWriter output;
  String name;
  RegistrationWaitState state;
  boolean registerred;
  boolean canUnregister;
  String message ;
  GamePlayWorker.CallBack callback ;

  public PlayerRegistrar(ServerSocket serverSocket, ConcurrentLinkedQueue<PlayerRegistrar> registrationQueue, Properties prop){

    this.serverSocket = serverSocket;
    this.registrationQueue = registrationQueue;
    this.prop = prop ;
    this.state = RegistrationWaitState.WAIT_HELLO ;
    this.registerred = false;
    this.canUnregister = true;
    this.GOODBYE_LOOP_TIMEOUT = Integer.parseInt(prop.getProperty("goodbye_timeout", "1")) * K_MILLISECS ;

  }

  @Override
  public void resetData() {
      // try { socket.close(); } catch (IOException e) {} ;
      socket = null;
      input = null;
      output = null;
      name = null;
      state = RegistrationWaitState.WAIT_HELLO;
      registerred = false ;
      canUnregister = true;
      message = null;
      callback = null;
  }

  @Override
  public void run() {
    /*
     *  block waiting for a client to conect on a the serverSocket
     */
     try {

         socket = serverSocket.accept();
         System.out.println("PlayerRegistrar socket accepted");
         input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
         output = new PrintWriter(socket.getOutputStream(), true);

     } catch (IOException e) {
    //     System.out.println("socket died when player attempted to connect: " + e);
     }

    /*
     *  after a TCP connection is created block waiting for the HELLO:PLAYERNAME messages
     */
     try {

         String command = null;
         try {

             // loop until receiving the HELLO message
             while (true) {

                 command = input.readLine();

                 if (command.startsWith(HELLO_REQUEST_MESSAGE) && command.length() > HELLO_REQUEST_MESSAGE.length()) {

                     // After receiving the HELLO:PLAYERNAME message:
                     //   set the player name in the player object.
                     name = command.substring(HELLO_REQUEST_MESSAGE.length(), command.length());
                     //   set the registerred flag in the player object.
                     registerred = true ;

                     // respond correctly
                     output.println(HELLO_RESPONSE_MESSAGE);
                     System.out.println(String.format("    %s has registerred", name));

                    /*
                     *  block on the regstrationQueue {
                     *     enqueue this player in the ready queue
                     *     if there are more than 1 players in the queue notify watchers of the queue object.
                     *  }
                     */
                     synchronized (registrationQueue) {
                        registrationQueue.add(this);
                        if (registrationQueue.size() > 1) {
                          registrationQueue.notify();
                          System.out.println(String.format("    registrationQueue has %d registrations", registrationQueue.size()));
                        }
                     }
                     // set next state to WAIT_GOODBYE
                     state = RegistrationWaitState.WAIT_GOODBYE;

                     break ;
                 }
           }

       } catch (NullPointerException npe) {
           // a NPE means the socket was close by the client
           // System.out.println("Socket disconnected before getting HELLO");
           return ;
       }

         // We have been registerred.  Now wait for either an unregister message from the client or
         // a thread wakeup (and unblock from the blocked socket) where we can check if we've been added to a game.
         /*
          *    Wait for a GOODBYE:PLAYERNAME messsage:
          */
          socket.setSoTimeout(GOODBYE_LOOP_TIMEOUT);  // 1 second
          while (canUnregister == true) {

              try {

                  command = input.readLine();
                  if (command.startsWith(GOODBY_REQUEST_MESSAGE) && command.length() > GOODBY_REQUEST_MESSAGE.length()) {
                      // set next state to WAIT_QUEUE
                      state = RegistrationWaitState.WAIT_QUEUE ;
                     /*
                      *  block on the registrationQueue {
                      *      if can-unregister == true
                      *          remove the player from the queue
                      *          mark the player as unregisterred
                      *          close the connection?  only if unregisterred == true
                      *  }
                      */
                     synchronized (registrationQueue) {
                        if (canUnregister == true){
                            if ( true == registrationQueue.remove(this)) {
                                // If removing works, then the registration was still in the queue and has not been picked up by
                                // a GamePlayWorker.  We've successfully unregisterred.  else, we were removed and could not unregister.
                                // perhaps overkill as canUnregister is tested first.
                                registerred = false;
                                socket.close();
                                System.out.println(String.format("    %s has un-registerred", name));
                            }
                        }
                     }
                  } else if (canUnregister == false ) {  // we are part of a game and all message are for the GamePlayWorker thread
                      // very "cludgie" but I could not find another way to unblock the readLine on the socket.
                      // at this point we are in game play.  The readLine above occurred before the game started.
                      // there is another block on IO on the socket in the GamePlayWorker thread looking for the String
                      // "chance-it".  Here I'll set a local variable that will be read by the other thread.
                      // could do a wait on the object in the other thread and a notify here.
                      // !!! perhaps this should be a callback too ?
                      this.message = command;
                      callback.setState(RegistrationWaitState.WAIT_TURN);
                      callback.setMessage(command) ;
                  }
              } catch (InterruptedIOException e) {
                    if (canUnregister == false) {
                      // This a call back as the state of this thread isn't guaranteed and could potentially reused by another Player registerring.
                      // If canUnregister == false we can't unregister.  An ActivePlayer object has been instantiated and this PlayerRegistration
                      // has had a callback set.
                      callback.setState(RegistrationWaitState.WAIT_TURN);
                      state = RegistrationWaitState.WAIT_TURN;
                    }
              } catch (NullPointerException npe) {
                  // a NPE here means socket closed by client
                  System.out.println("Socket disconnected before getting GOODBYE");
                  // need to unregister
                  synchronized (registrationQueue) {
                     if (canUnregister == true){
                         if ( true == registrationQueue.remove(this)) {
                             // If removing works, then the registration was still in the queue and has not been picked up by
                             // a GamePlayWorker.  We've successfully unregisterred.  else, we were removed and could not unregister.
                             // perhaps overkill as canUnregister is tested first.
                             registerred = false;
                             socket.close();
                             System.out.println(String.format("    %s has un-registerred", name));
                         }
                     }
                  }
                  return ;
              }
        }
     } catch (IOException e) {}
    /*
     *    wait for a GOODBYE:PLAYERNAME messsage:
     *          block on the registrationQueue {
     *              if can-unregister == true
     *                  remove the player from the queue
     *                  mark the player as unregisterred
     *                  close the connection?  only if unregisterred == true
     *          }
     *          wait for a reregistration?  meh..not described in the functionality.
     *          if we unregisterred we should close the connection and fall of the end of the run() method.
     *          if we can't unregister ...
     *              1) we were blocking on a read on the socket .. This thread will be interruped, canUnregister will == false
     *                   we should fall off the end of the thread.
     *              2) we received an unregister, before we could remove ourselves we were added to a game.
     *                   blocking then unblocking on the queue, we will learn that we can't unregister, so we do nothing and fall offf the end of the run()
     *                      -- we've been added to a game so we should not close the connection here (it will be closed when the game finishes) --
     *              3) we received an unregister, before a game starts, we block on the queue and remove ourselves.  The game thread enblocks
     *                   on the queue and finds there aren't enough players to merit a game so it does nothing (loops and reblocks on the queue)
     *                   after we unregister we close the connection and fall off the end of the run()
     *
     */

      // get a socket
      // listen for HELLO:PLAYER name
      // if message == "HELLO:PLAYER NAME"
      //    block on QUEUE {
      //        insert this player into the queue
      //        if more that 1 player is in the queue notify queue listeners
      //    }
      //    # the player is registerred but could wait a long time for another player to register.
      //    # the therefore could unregister, so..
      //    continue listening on the socket for "GOODBYE" (until canUnregister == false)
      //      # blocking on the socket this thread can be interrupted by a Game indicating that the player has been added to a game.
      //      # the socket and the name will be referenceed in a ActivePlayer object in the Game so from an interrup we should fall of
      //      #  the end of the code run() so that the thread can be reclaimed as part of the pool.
      // if message == "GOODBYE:PLAYER NAME"
      //    block on QUEUE {
      //       the logic is expressed better above.
      //    }
  }   // end of the PlayerRegistrar thread
}
