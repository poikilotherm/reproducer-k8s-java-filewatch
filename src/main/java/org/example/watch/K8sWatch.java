package org.example.watch;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import sun.nio.ch.ThreadPool;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.nio.file.StandardWatchEventKinds.*;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;

@Startup
@Singleton
public class K8sWatch {
    
    final class DirPropertyWatcher implements Runnable {
        
        private final Logger logger = Logger.getLogger(K8sWatch.class.getName());
        private final WatchService watcher = FileSystems.getDefault().newWatchService();
        private final ConcurrentHashMap<WatchKey, Path> watchedFileKeys = new ConcurrentHashMap<>();
        
        DirPropertyWatcher(Path topmostDir) throws IOException {
            logger.info("Trying to build a new watcher...");
            if (topmostDir != null && Files.exists(topmostDir) && Files.isDirectory(topmostDir) && Files.isReadable(topmostDir)) {
                registerAll(topmostDir);
            } else {
                throw new IOException("Given directory '"+topmostDir+"' is no directory or cannot be read.");
            }
        }
        
        void registerAll(Path dir) throws IOException {
            // register file watchers recursively (they don't attach to subdirs...)
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
                {
                    // only register subdirectories if the directory itself is suitable.
                    if ( isAptDir(dir) ) {
                        register(dir);
                        return FileVisitResult.CONTINUE;
                    }
                    return FileVisitResult.SKIP_SUBTREE;
                }
            });
        }
    
        /**
         * Register the directory, but leave out non-suitable ones (like those with a "." in their name)
         * @param dir
         * @throws IOException
         */
        void register(Path dir) throws IOException {
            WatchKey key = dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
            watchedFileKeys.putIfAbsent(key, dir);
            logger.info("Registered \""+dir+"\" as key \""+key+"\".");
        }
    
        /**
         * Check path to be a directory, readable by us, but ignore if starting with a "."
         * (as done for K8s secrets mounts).
         * @param path
         * @return true if suitable, false if not.
         * @throws IOException when path cannot be resolved, maybe because of a broken symlink.
         */
        boolean isAptDir(Path path) throws IOException {
            return path != null && Files.exists(path) &&
                   Files.isDirectory(path) && Files.isReadable(path) &&
                   !path.getFileName().toString().startsWith(".");
        }
    
        /**
         * Check if the file exists (follows symlinks), is a regular file (following symlinks), is readable (following symlinks),
         * and the filename does not start with a "." (not following the symlink!)
         * @param path
         * @return true if suitable file, false if not.
         * @throws IOException when path cannot be accessed or resolved etc.
         */
        boolean isAptFile(Path path) throws IOException {
            return path != null && Files.exists(path) &&
                   Files.isRegularFile(path) && Files.isReadable(path) &&
                   !path.getFileName().toString().startsWith(".");
        }
        
        @Override
        public void run() {
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException ex) {
                logger.info("Shutting down watcher thread.");
                return;
            }
            
            Path workDir = watchedFileKeys.get(key);
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                
                @SuppressWarnings("unchecked")
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path fileName = ev.context();
                Path path = workDir.resolve(fileName);
                
                logger.info("Detected change: "+fileName.toString()+" : "+kind.toString());
                
                try {
                    // new directory to be watched and traversed
                    if (kind == ENTRY_CREATE && isAptDir(path)) {
                        logger.info("Registering new paths.");
                        registerAll(path);
                    }
                    // new or updated file found (new = create + modify on content save)
                    // or new symlink found (symlinks are create only!) AND pointing to a suitable file
                    if ( isAptFile(path) &&
                         (kind == ENTRY_MODIFY || (kind == ENTRY_CREATE && Files.isSymbolicLink(path)) ) ) {
                        logger.info("Processing new or updated file \""+path.toString()+"\".");
                    }
                    if (Files.notExists(path) && ! watchedFileKeys.containsValue(path) && kind == ENTRY_DELETE) {
                        logger.info("Removing deleted file \""+path.toString()+"\".");
                    }
                } catch (IOException e) {
                    logger.log(Level.SEVERE, "Could not process event '"+kind+"' on '"+path+"'", e);
                }
            }
            
            // Reset key (obligatory) and remove from set if directory no longer accessible
            boolean valid = key.reset();
            if (!valid) {
                logger.info("Removing watcher for key \""+key+"\".");
                watchedFileKeys.remove(key);
            }
        }
    }
    
    private final Logger logger = Logger.getLogger(K8sWatch.class.getName());
    
    @Inject
    @ConfigProperty(name = "org.example.dir")
    private String directory;
    
    private ScheduledExecutorService exec = Executors.newScheduledThreadPool(3);
    
    @PostConstruct
    public void initWatcher() {
        try {
            logger.info("Configured directory: "+directory);
            exec.scheduleWithFixedDelay(new DirPropertyWatcher(Paths.get(directory)), 0, 1, TimeUnit.SECONDS);
        } catch (IOException e) {
            logger.log(Level.WARNING, "IOException on watcher creation.", e);
        }
    }
    
    @PreDestroy
    public void shutdown() {
        exec.shutdown();
    }
}
