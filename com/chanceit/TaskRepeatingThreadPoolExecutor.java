package com.chanceit;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;


public class TaskRepeatingThreadPoolExecutor extends ThreadPoolExecutor {
  public TaskRepeatingThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue) {
    super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue);
  }

  @Override
  protected void afterExecute(Runnable r, Throwable t) {
    super.afterExecute(r, t);
    //PlayerRegistrar pr = (PlayerRegistrar)r;
    RunnableRecycler rr = (RunnableRecycler)r;
    rr.resetData();
    this.execute(r);  // may need to cast back to runnable ?
    // System.out.println("Task Resubmitted: " + r) ;
  }
}
