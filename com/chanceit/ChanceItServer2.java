package com.chanceit;

import com.chanceit.GamePlayWorker;
import com.chanceit.PlayerRegistrar;
import com.chanceit.RegistrationWaitState;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ChanceItServer2 {

  private static final String CONFIG_FILE = "./config.properties";




  public static void main(String[] args) throws Exception {

    /*
     * gather Properties from config.properties
     */
    Properties prop = new Properties();
    InputStream input = null;

    // The following two sets of variables will be reassigned by values from config.properties
    int SERVER_PORT                      = 1099 ;
    String HOST                          = "localhost" ;
    int PLAYER_REGISTRAR_CORE_POOL_SIZE  = 2 ;
    int PLAYER_REGISTRAR_MAX_POOL_SIZE   = 4 ;
    int PLAYER_REGISTRAR_KEEP_ALIVE_TIME = 5000 ;
    int PLAYER_REGISTRAR_THREAD_COUNT    = 10 ;

    int GAME_PLAY_WORKER_CORE_POOL_SIZE  = 2 ;
    int GAME_PLAY_WORKER_MAX_POOL_SIZE   = 4 ;
    int GAME_PLAY_WORKER_KEEP_ALIVE_TIME = 5000 ;
    int GAME_PLAY_WORKER_THREAD_COUNT    = 10 ;

    String GAME_LOG                      = "./gameLog.txt" ;

    try {
        input = new FileInputStream(CONFIG_FILE);

        prop.load(input);

        /*
         *  Server Settings
         */
        // port
        SERVER_PORT = Integer.parseInt(prop.getProperty("port", "1099"));

        // host IP ?
        HOST = prop.getProperty("host", "localhost");  // not actuall used.



        /*
         * Player Registrar Pool settings
         */
        // player pool init size
        PLAYER_REGISTRAR_CORE_POOL_SIZE = Integer.parseInt(prop.getProperty("registrar_pool_init_size", "2"));

        // player pool max size
        PLAYER_REGISTRAR_MAX_POOL_SIZE = Integer.parseInt(prop.getProperty("registrar_pool_max_size", "4"));

        // player pool keep alive.
        PLAYER_REGISTRAR_KEEP_ALIVE_TIME = Integer.parseInt(prop.getProperty("registrar_pool_keepalive", "5")) * 1000; // time in miliseconds

        // player pool thread count
        PLAYER_REGISTRAR_THREAD_COUNT = Integer.parseInt(prop.getProperty("registrar_pool_thread_count", "4")); // must be an even number



        /*
         * GamePlayerWorker Pool settings
         */
        // GamePlayWorker pool init size
        GAME_PLAY_WORKER_CORE_POOL_SIZE = Integer.parseInt(prop.getProperty("gameplayworker_pool_init_size", "2"));

        // GamePlayWorker pool max size
        GAME_PLAY_WORKER_MAX_POOL_SIZE = Integer.parseInt(prop.getProperty("gameplayworker_pool_max_size", "4"));

        // GamePlayWorker pool keep alive.
        GAME_PLAY_WORKER_KEEP_ALIVE_TIME = Integer.parseInt(prop.getProperty("gameplayworker_pool_keepalive", "5")) * 1000; // time in miliseconds

        // GamePlayWorker pool thread count
        GAME_PLAY_WORKER_THREAD_COUNT = Integer.parseInt(prop.getProperty("gameplayworker_pool_thread_count", "4")); // must be an even number

        /*
         *  Log file for Game outcomes
         */
        GAME_LOG = prop.getProperty("game_log", "./gameLog.csv") ;

    } catch (IOException e ) {
        e.printStackTrace() ;
    }
      finally {
        if (input != null) {
            input.close();
        }
      }

    System.out.println("********************************");
    System.out.println("ChanceIt Server is staring up...");
    InetAddress addr = InetAddress.getByName(HOST);
    ServerSocket listener = new ServerSocket(SERVER_PORT, 50, addr);
    System.out.println("Listening on: " + HOST + ":" + SERVER_PORT) ;
    System.out.println("********************************");


    System.out.println("Logging outcomes to: " + GAME_LOG) ;
    BufferedWriter gameLog = null ;
    try {

      FileWriter fstream = new FileWriter(GAME_LOG) ;
      gameLog = new BufferedWriter(fstream);

      // write the CSV header
      gameLog.write("winner,wscore,loser,lscore,how\n");
      gameLog.flush() ;

    } catch (IOException e) {}

    /*
     * instantiate a ConcurentLinkedQueue:
     *  • PlayerRegistrar threads will produce playerRegistrations and add/remove (unregister) them to the queue
     *  • GamePlayWorker threads will consume PlayerRegisratations and execute the games.
     */

     ConcurrentLinkedQueue<PlayerRegistrar> registrationQueue = new ConcurrentLinkedQueue<PlayerRegistrar>();
     System.out.println("    registrationQueue created...");

    /*
     * spark up a pool of PlayerRegistrar(s)
     */
     ExecutorService playerRegistrarPool = new TaskRepeatingThreadPoolExecutor(
        PLAYER_REGISTRAR_CORE_POOL_SIZE,
        PLAYER_REGISTRAR_MAX_POOL_SIZE,
        PLAYER_REGISTRAR_KEEP_ALIVE_TIME * 1000,
        TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<Runnable>()
        ) ;

    /*
    PoolMonitor regstrarPoolMonitor = new PoolMonitor((ThreadPoolExecutor)playerRegistrarPool, 3, "PlayerRegistrarPool") ;
    Thread registrarMonitorThread = new Thread(regstrarPoolMonitor) ;
    registrarMonitorThread.start() ;
    */

    System.out.println("    playerRegistrarPool executor created...");
    // System.out.println("      playerRegistrarPool PoolMonitor created") ;

     // spark up some workers
     for (int i = 0; i< PLAYER_REGISTRAR_THREAD_COUNT; i++) {

         Runnable playerRegistrar = new PlayerRegistrar(listener, registrationQueue, prop) ;
         playerRegistrarPool.execute(playerRegistrar) ;

     }
     System.out.println(String.format("    created %d PlayerRegistrar Threads...", PLAYER_REGISTRAR_THREAD_COUNT));

    /*
     * spark up a pool of GamePlayWorker(s)
     */
     ExecutorService gamePlayWorkerPool = new TaskRepeatingThreadPoolExecutor(
        GAME_PLAY_WORKER_CORE_POOL_SIZE,
        GAME_PLAY_WORKER_MAX_POOL_SIZE,
        GAME_PLAY_WORKER_KEEP_ALIVE_TIME * 1000,
        TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<Runnable>()
        ) ;

    /*
    PoolMonitor gamePlayWorkerPoolMonitor = new PoolMonitor((ThreadPoolExecutor)gamePlayWorkerPool, 3, "GamePlayWorkerPool") ;
    Thread gamePlayMonitorThread = new Thread(gamePlayWorkerPoolMonitor) ;
    gamePlayMonitorThread.start() ;
    */

    System.out.println("    gamePlayWorkerPool executor created...");
    // System.out.println("      gamePlayWorkerPool PoolMonitor created");
     // spark up some workers
     for (int i = 0; i< GAME_PLAY_WORKER_THREAD_COUNT; i++) {

         Runnable gamePlayWorker = new GamePlayWorker(registrationQueue, prop, gameLog) ;
         gamePlayWorkerPool.execute(gamePlayWorker) ;

     }
     System.out.println(String.format("    created %d GamePlayWorker Threads...", GAME_PLAY_WORKER_THREAD_COUNT));


    /*
     * wait for the whole thing to finish ?  quit gracefully ?
     */
  }

}
