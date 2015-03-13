package org.apache.hadoop.hdfs.server.datanode;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.IOException;

/**
 * Created by Jiessie on 10/3/15.
 */
public class PendingMove implements Runnable{

  private static final Logger LOG = Logger.getLogger(PendingMove.class);
  public File fromSubdir;
  public File toSubdir;
  public Volume fromVolume;
  public Volume toVolume;
  public long fromSubdirSize;

  public PendingMove(Source source, Target target){
    this.fromSubdir = source.getFile();
    this.toSubdir = target.getFile();
    this.fromVolume = source.getVolume();
    this.toVolume = target.getVolume();
    this.fromSubdirSize = source.getFileSize();
  }

  public PendingMove(PendingMove move){
    this.fromSubdir = move.fromSubdir;
    this.toSubdir = move.toSubdir;
    this.fromSubdirSize = move.fromSubdirSize;
    this.toVolume = move.toVolume;
    this.fromVolume = move.fromVolume;
  }

  public PendingMove(final File fromSubdir, final File toSubdir, final Volume fromVolume, final Volume toVolume, final long fromSubdirSize) {
    this.fromSubdir = fromSubdir;
    this.toSubdir = toSubdir;
    this.fromVolume = fromVolume;
    this.toVolume = toVolume;
    this.fromSubdirSize = fromSubdirSize;
  }

  public String toString(){
    return String.format("PendingMove[%d]: fromVolume[%s],fromSubdir[%s],toVolume[%s],toSubdir[%s]",this.fromSubdirSize,this.fromVolume.toString(),this.fromSubdir,this.toVolume.toString(),this.toSubdir);
  }

  /**
   * doMove fromSubdir to toSubdir with copying and moving out.
   * @return
   * @throws IOException
   */
  public void run(){
    //1. copy fromSubdir to toSubdir, because the fromVolume may not have enough space to copy it self.
    try{
      try {
        FileUtils.copyDirectory(this.fromSubdir, this.toSubdir);
      }catch(IOException ex){
        LOG.error("failed to copyDirectory, "+this.fromSubdir.getAbsolutePath()+" ---->"+this.toSubdir.getAbsolutePath() + "Exception occured: "+ ExceptionUtils.getFullStackTrace(ex));
        //check and roll back.
        FileUtils.deleteDirectory(this.toSubdir);
        return;
      }

      //2. move fromSubdir to backup folder of this volume (outside the “current“” folder)
      try{
        FileUtils.moveDirectory(this.fromSubdir, new File(this.fromVolume.getHadoopV1BackupDir(),this.fromSubdir.getName()+"/"));
      }catch(Exception ex){
        LOG.error("failed to move out the from directory," + ExceptionUtils.getFullStackTrace(ex));
        // if the original is not deleted, it will be OK, just delete it, since there is already a copy in TO volume.
        // when needing rollback, just copy toSubdir back to from.
        FileUtils.deleteDirectory(this.fromSubdir);
        return ;
      }
    }catch(IOException ex){
      LOG.error("failed to move"+ ExceptionUtils.getFullStackTrace(ex));
    }
  }

  /**
   * submit the copy and change, delete the backup.
   * @return
   * @throws IOException
   */
  public boolean submitAndClean() throws IOException{
    FileUtils.deleteDirectory(new File(this.fromVolume.getHadoopV1BackupDir(),this.fromSubdir.getName()+"/"));
    LOG.info("clean the backup fromsubdir"+ this.fromSubdir.getAbsolutePath());
    return false;
  }

  /**
   * recover the fromSubdir, and clean the other Subdir.(backup and to)
   * @return
   * @throws IOException
   */
  public boolean doRollBack() throws IOException{
    try{
      File backupFile = new File(this.fromVolume.getHadoopV1BackupDir(),this.fromSubdir.getName()+"/");
      if(!backupFile.exists()){
        //TODO: if backup is not here, move ToSubdir back directly, for rollback after restart datanode.
        FileUtils.moveDirectory(this.toSubdir,this.fromSubdir);
      }else {
        FileUtils.moveDirectory(backupFile,this.fromSubdir);
        FileUtils.deleteDirectory(this.toSubdir);
      }
      LOG.info("succeed to rollback");
      return true;
    }catch(Exception ex){
      LOG.error("rollback failed, need rollback manually."+ ExceptionUtils.getFullStackTrace(ex));
    }
    return false;
  }
}
