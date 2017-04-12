/*
 * Copyright 2003-2017 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.marklogic.mapreduce;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipInputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

import com.marklogic.mapreduce.utilities.AssignmentManager;
import com.marklogic.mapreduce.utilities.AssignmentPolicy;
import com.marklogic.mapreduce.utilities.ForestHost;
import com.marklogic.mapreduce.utilities.InternalUtilities;
import com.marklogic.mapreduce.utilities.StatisticalAssignmentPolicy;
import com.marklogic.xcc.Content;
import com.marklogic.xcc.ContentCapability;
import com.marklogic.xcc.ContentCreateOptions;
import com.marklogic.xcc.ContentFactory;
import com.marklogic.xcc.ContentPermission;
import com.marklogic.xcc.ContentSource;
import com.marklogic.xcc.DocumentFormat;
import com.marklogic.xcc.DocumentRepairLevel;
import com.marklogic.xcc.RequestOptions;
import com.marklogic.xcc.Session;
import com.marklogic.xcc.impl.SessionImpl;
import com.marklogic.xcc.Session.TransactionMode;
import com.marklogic.xcc.exceptions.ContentInsertException;
import com.marklogic.xcc.exceptions.RequestException;
import com.marklogic.xcc.exceptions.RequestServerException;
import com.marklogic.xcc.exceptions.RetryableQueryException;
import com.marklogic.xcc.exceptions.XQueryException;

/**
 * MarkLogicRecordWriter that inserts content to MarkLogicServer.
 * 
 * @author jchen
 *
 */
public class ContentWriter<VALUEOUT> 
extends MarkLogicRecordWriter<DocumentURI, VALUEOUT> implements MarkLogicConstants {
    public static final Log LOG = LogFactory.getLog(ContentWriter.class);
    
    public static final String ID_PREFIX = "#";

    /**
     * Directory of the output documents.
     */
    protected String outputDir;
    
    /**
     * Content options of the output documents.
     */
    protected ContentCreateOptions options;
    
    /**
     * A map from a host to a ContentSource. 
     */
    protected Map<String, ContentSource> hostSourceMap;
    
    /**
     * Content lists for each forest.
     */
    protected Content[][] forestContents;
    
    /**
     * An array of forest/host ids
     */
    protected String[] forestIds;

    /**
     * An array of current replica
     */
    protected int[] curReplica;

    /**
     * An array of blacklist host 
     */
    protected boolean[] blacklist;
    
    /** 
     * Counts of documents per forest.
     */
    protected int[] counts;
    
    /**
     * URIs of the documents in a batch.
     */
    protected HashMap<Content, DocumentURI>[] pendingUris;
    
    /**
     * URIs of the documents to be committed.
     */
    protected List<DocumentURI>[] commitUris;
    
    /**
     * Whether in fast load mode.
     */
    protected boolean fastLoad;
    
    /**
     * Batch size.
     */
    protected int batchSize;
    
    /**
     * Counts of requests per forest.
     */
    protected int[] stmtCounts;
    
    /**
     * Sessions per forest.
     */
    protected Session[] sessions;
    
    private boolean formatNeeded;
    
    private FileSystem fs;
    
    protected InputStream is;
    
    private boolean streaming;
    
    private boolean tolerateErrors;

    private RequestOptions requestOptions;

    protected AssignmentManager am;
    
    //fIdx cached for statistical policy
    protected int sfId;
    
    protected boolean countBased;
    
    /** role-id -> role-name mapping **/
    protected LinkedMapWritable roleMap;
    
    protected HashMap<String,ContentPermission[]> permsMap;
    
    protected int succeeded = 0;
    
    protected int failed = 0;
    
    protected boolean needCommit = false;
    
    protected int hostId = 0;
    
    protected boolean isCopyColls;
    protected boolean isCopyQuality;
    protected boolean isCopyMeta;
    
    protected long effectiveVersion;
    
    protected boolean isTxnCompatible = false;
    
    public ContentWriter(Configuration conf, 
        Map<String, ContentSource> hostSourceMap, boolean fastLoad) {
        this(conf, hostSourceMap, fastLoad, null);
    }
    
    public ContentWriter(Configuration conf,
        Map<String, ContentSource> hostSourceMap, boolean fastLoad,
        AssignmentManager am) {
        super(conf, null);
   
        effectiveVersion = am.getEffectiveVersion();
        isTxnCompatible = effectiveVersion == 0;
        
        this.fastLoad = fastLoad;
        
        this.hostSourceMap = hostSourceMap;
        
        this.am = am;

        requestOptions = new RequestOptions();
        requestOptions.setMaxAutoRetry(0);
        
        permsMap = new HashMap<String,ContentPermission[]>();
        
        // key order in key set is guaranteed by LinkedHashMap,
        // i.e., the order keys are inserted

        int srcMapSize;

        if (fastLoad) {
            forestIds = am.getMasterIds().clone();
            srcMapSize = forestIds.length;
            curReplica = new int[srcMapSize];
            for (int i = 0; i < srcMapSize; i++) {
               curReplica[i] = 0;
            }
        } else {
            srcMapSize = hostSourceMap.size();
            forestIds = new String[srcMapSize];
            forestIds = hostSourceMap.keySet().toArray(forestIds);
            blacklist = new boolean[srcMapSize];
            for (int i = 0; i < srcMapSize; i++) {
               blacklist[i] = false;
            }
        }

        hostId = (int)(Math.random() * srcMapSize);
        
        // arraySize is the number of forests in fast load mode; 1 otherwise.
        int arraySize = fastLoad ? srcMapSize : 1;
        sessions = new Session[arraySize];
        stmtCounts = new int[arraySize];
        
        outputDir = conf.get(OUTPUT_DIRECTORY);
        batchSize = conf.getInt(BATCH_SIZE, DEFAULT_BATCH_SIZE);
        
        pendingUris = new HashMap[arraySize];
        for (int i = 0; i < arraySize; i++) {
            pendingUris[i] = new HashMap<Content, DocumentURI>();
        }

        if (fastLoad
            && (am.getPolicy().getPolicyKind() == AssignmentPolicy.Kind.STATISTICAL
            || am.getPolicy().getPolicyKind() == AssignmentPolicy.Kind.RANGE
            || am.getPolicy().getPolicyKind() == AssignmentPolicy.Kind.QUERY)) {
            countBased = true;
            if (batchSize > 1) {           
                forestContents = new Content[1][batchSize];
                counts = new int[1];
            }
            sfId = -1;
        } else {
            if (batchSize > 1) {           
                forestContents = new Content[arraySize][batchSize];
                counts = new int[arraySize];                
            }
            sfId = 0;
        }
        
        String[] perms = conf.getStrings(OUTPUT_PERMISSION);
        List<ContentPermission> permissions = null;
        if (perms != null && perms.length > 0) {
            int i = 0;
            while (i + 1 < perms.length) {
                String roleName = perms[i++];
                if (roleName == null || roleName.isEmpty()) {
                    LOG.error("Illegal role name: " + roleName);
                    continue;
                }
                String perm = perms[i].trim();
                ContentCapability capability = null;
                if (perm.equalsIgnoreCase(ContentCapability.READ.toString())) {
                    capability = ContentCapability.READ;
                } else if (perm.equalsIgnoreCase(ContentCapability.EXECUTE.toString())) {
                    capability = ContentCapability.EXECUTE;
                } else if (perm.equalsIgnoreCase(ContentCapability.INSERT.toString())) {
                    capability = ContentCapability.INSERT;
                } else if (perm.equalsIgnoreCase(ContentCapability.UPDATE.toString())) {
                    capability = ContentCapability.UPDATE;
                } else if (perm.equalsIgnoreCase(ContentCapability.NODE_UPDATE.toString())) {
                    capability = ContentCapability.NODE_UPDATE;
                } else {
                    LOG.error("Illegal permission: " + perm);
                }
                if (capability != null) {
                    if (permissions == null) {
                        permissions = new ArrayList<ContentPermission>();
                    }
                    permissions.add(new ContentPermission(capability, roleName));
                }
                i++;
            }
        }
        
        options = new ContentCreateOptions();
        String[] collections = conf.getStrings(OUTPUT_COLLECTION);
        if (collections != null) {
            for (int i = 0; i < collections.length; i++) {
                collections[i] = collections[i].trim();
            }
            options.setCollections(collections);
        }
        
        options.setQuality(conf.getInt(OUTPUT_QUALITY, 0));
        if (permissions != null) {
            options.setPermissions(permissions.toArray(
                    new ContentPermission[permissions.size()]));
        } 
        String contentTypeStr = conf.get(CONTENT_TYPE, DEFAULT_CONTENT_TYPE);
        ContentType contentType = ContentType.valueOf(contentTypeStr);
        if (contentType == ContentType.UNKNOWN) {
            formatNeeded = true;
        } else {
            options.setFormat(contentType.getDocumentFormat());
        }
        
        options.setLanguage(conf.get(OUTPUT_CONTENT_LANGUAGE));
        String repairLevel = conf.get(OUTPUT_XML_REPAIR_LEVEL,
                DEFAULT_OUTPUT_XML_REPAIR_LEVEL).toLowerCase();
        options.setNamespace(conf.get(OUTPUT_CONTENT_NAMESPACE));
        if (DocumentRepairLevel.DEFAULT.toString().equals(repairLevel)){
            options.setRepairLevel(DocumentRepairLevel.DEFAULT);
        }
        else if (DocumentRepairLevel.NONE.toString().equals(repairLevel)){
            options.setRepairLevel(DocumentRepairLevel.NONE);
        }
        else if (DocumentRepairLevel.FULL.toString().equals(repairLevel)){
            options.setRepairLevel(DocumentRepairLevel.FULL);
        }
        
        streaming = conf.getBoolean(OUTPUT_STREAMING, false);
        tolerateErrors = conf.getBoolean(OUTPUT_TOLERATE_ERRORS, false);
        
        String encoding = conf.get(OUTPUT_CONTENT_ENCODING);
        if (encoding != null) {
            options.setEncoding(encoding);
        }
        
        options.setTemporalCollection(conf.get(TEMPORAL_COLLECTION));
        
        needCommit = needCommit();
        if (needCommit) {
            commitUris = new ArrayList[arraySize];
            for (int i = 0; i < arraySize; i++) {
                commitUris[i] = new ArrayList<DocumentURI>(txnSize*batchSize);
            }
        }
        
        isCopyColls = conf.getBoolean(COPY_COLLECTIONS, true);
        isCopyQuality = conf.getBoolean(COPY_QUALITY, true);
        isCopyMeta = conf.getBoolean(COPY_METADATA, true);
    }
    
    protected boolean needCommit() {
        return txnSize > 1 || (batchSize > 1 && tolerateErrors);
    }

    protected Content createContent(DocumentURI key, VALUEOUT value) 
            throws IOException {
        String uri = key.getUri();
        Content content = null;
        if (value instanceof Text) {
            if (formatNeeded) {
                options.setFormat(DocumentFormat.TEXT);
                formatNeeded = false;
            }
            options.setEncoding(DEFAULT_OUTPUT_CONTENT_ENCODING);
            content = ContentFactory.newContent(uri, 
                    ((Text) value).getBytes(), 0, 
                    ((Text)value).getLength(), options);
        } else if (value instanceof MarkLogicNode) {
            if (formatNeeded) {
                options.setFormat(DocumentFormat.XML);
                formatNeeded = false;
            }
            content = ContentFactory.newContent(uri, 
                    ((MarkLogicNode)value).get(), options);
        } else if (value instanceof ForestDocument) {
            ContentCreateOptions newOptions = options;
            if (isCopyColls || isCopyMeta || isCopyQuality) {
                newOptions = (ContentCreateOptions)options.clone();
            }
            content = ((ForestDocument)value).createContent(uri, newOptions,
                    isCopyColls, isCopyMeta, isCopyQuality);
        } else if (value instanceof BytesWritable) {
            if (formatNeeded) {
                options.setFormat(DocumentFormat.BINARY);
                formatNeeded = false;
            }
            content = ContentFactory.newContent(uri, 
                    ((BytesWritable) value).getBytes(), 0, 
                    ((BytesWritable) value).getLength(), options);               
        } else if (value instanceof CustomContent) { 
            ContentCreateOptions newOptions = options;
            if (batchSize > 1) {
                newOptions = (ContentCreateOptions)options.clone();
            }
            content = ((CustomContent) value).getContent(conf, newOptions, 
                    uri);
        } else if (value instanceof DatabaseDocument) {
            DatabaseDocument doc = (DatabaseDocument)value;
            if (formatNeeded) {
                options.setFormat(doc.getContentType().getDocumentFormat());
                formatNeeded = false;
            }
            options.setEncoding(DEFAULT_OUTPUT_CONTENT_ENCODING);
            if (doc.getContentType() == ContentType.BINARY) {
                content = ContentFactory.newContent(uri, 
                        doc.getContentAsByteArray(), options);
            } else {
                content = ContentFactory.newContent(uri, 
                        doc.getContentAsText().getBytes(), options);
            }
        } else if (value instanceof StreamLocator) {
            Path path = ((StreamLocator)value).getPath();
            if (fs == null) {         
                URI fileUri = path.toUri();
                fs = FileSystem.get(fileUri, conf);
            }
            switch (((StreamLocator)value).getCodec()) {
                case GZIP:
                    InputStream fileIn = fs.open(path);
                    is = new GZIPInputStream(fileIn);
                    break;
                case ZIP:
                    if (is == null) {
                        InputStream zipfileIn = fs.open(path);
                        ZipInputStream zis = new ZipInputStream(zipfileIn);
                        is = new ZipEntryInputStream(zis, path.toString());
                    }
                    break;
                case NONE:
                    is = fs.open(path);
                    break;
                default:
                    LOG.error("Unsupported compression codec: " + 
                        ((StreamLocator)value).getCodec() + " for document " +
                        key);
                    return content;
            }
            if (streaming) {
                content = ContentFactory.newUnBufferedContent(uri, is, 
                        options);
            } else {
                content = ContentFactory.newContent(uri, is, options);
            }

        } else {
            throw new UnsupportedOperationException(value.getClass()
                    + " is not supported.");
        }
        return content;
    }

    /**
     * Insert batch, log errors and update stats.
     * 
     * @param batch batch of content to insert
     * @param id forest Id
     */
    protected void insertBatch(Content[] batch, int id) 
    throws IOException {
        int t = 0;
        final int maxRetries = 15;
        int sleepTime = 100;
        final int maxSleepTime = 60000;
        while (t++ < maxRetries) {
            try {
                if (t > 1) { 
                    LOG.info("Retrying document insert " + t);
                }
                List<RequestException> errors = 
                    sessions[id].insertContentCollectErrors(batch);
                if (errors != null) {
                    for (RequestException ex : errors) {
                        Throwable cause = ex.getCause();
                        if (cause != null) {
                            if (cause instanceof XQueryException) {
                                LOG.error(
                                    ((XQueryException)cause).getFormatString());
                            } else {
                                LOG.error(cause.getMessage());
                            }
                        }
                        if (ex instanceof ContentInsertException) {
                            Content content = 
                                    ((ContentInsertException)ex).getContent();
                            if (!needCommit &&
                                batch[batch.length-1] == content) {
                                // remove the failed content from pendingUris
                                for (Content fc : batch) {
                                    DocumentURI failedUri = 
                                            pendingUris[id].remove(fc);
                                    if (failedUri != null) {
                                        failed++;
                                        LOG.warn("Failed document " + failedUri);
                                    }
                                }
                            } else {
                                DocumentURI failedUri = 
                                        pendingUris[id].remove(content);
                                failed++;
                                if (failedUri != null) {
                                    LOG.warn("Failed document " + failedUri);
                                }
                            }
                        }
                    }
                }
            } catch (RetryableQueryException e) {
                // log error and continue on RequestServerException
                LOG.error(e.getFormatString());

                if (needCommit && !commitUris[id].isEmpty()) {
                    rollback(id);
                }

                if (t < maxRetries) {
                    if (sessions[id] != null) {
                       sessions[id].close();
                    }
                    try {
                        Thread.sleep(sleepTime);
                    } catch (Exception e2) {
                    }
                    sleepTime = sleepTime * 2;
                    if (sleepTime > maxSleepTime) 
                        sleepTime = maxSleepTime;

                    sessions[id] = getSession(id, false);
                    continue;
                } else {
                    failed += batch.length;
                    // remove the failed content from pendingUris
                    for (Content fc : batch) {
                        DocumentURI failedUri = pendingUris[id].remove(fc);
                        LOG.warn("Failed document " + failedUri);
                    }
                }
            } catch (RequestServerException e) {
                // log error and continue on RequestServerException
                if (e instanceof XQueryException) {
                    LOG.error(((XQueryException)e).getFormatString());
                } else {
                    LOG.error(e.getMessage());
                }
                if (needCommit && !commitUris[id].isEmpty()) {
                    failed += commitUris[id].size();
                    for (DocumentURI failedUri : commitUris[id]) {
                        LOG.warn("Failed document: " + failedUri);
                    }
                    commitUris[id].clear();
                }
                if (t < maxRetries) {
                    sessions[id] = getSession(id, true);
                    continue;
                }

                failed += batch.length;   
                // remove the failed content from pendingUris
                for (Content fc : batch) {
                    DocumentURI failedUri = pendingUris[id].remove(fc);
                    LOG.warn("Failed document " + failedUri);
                }

            } catch (RequestException e) {
                if (needCommit && !commitUris[id].isEmpty()) {
                    failed += commitUris[id].size();
                    for (DocumentURI failedUri : commitUris[id]) {
                        LOG.warn("Failed document: " + failedUri);
                    }
                    commitUris[id].clear();
                }
                if (t < maxRetries) {
                    sessions[id] = getSession(id, true);
                    continue;
                }

                if (countBased) {
                    rollbackCount(id);
                }
                pendingUris[id].clear();
                throw new IOException(e);
            }
            break;
        }

        if (needCommit) {
            // move uris from pending to commit
            for (DocumentURI uri : pendingUris[id].values()) {
                commitUris[id].add(uri);
            }
        } else {
            succeeded += pendingUris[id].size();
        }
        pendingUris[id].clear();
    }

    protected void rollback(int id) throws IOException {
        try {
            sessions[id].rollback();
            failed += commitUris[id].size();
            for (DocumentURI failedUri : commitUris[id]) {
                LOG.warn("Failed document: " + failedUri);
            }
            commitUris[id].clear();
        } catch (RequestServerException e) {
            LOG.error("Error rollback transaction", e);
            failed += commitUris[id].size();
            for (DocumentURI failedUri : commitUris[id]) {
                LOG.warn("Failed document: " + failedUri);
            }
            commitUris[id].clear();
        } catch (RequestException e) {
            if (sessions[id] != null) {
                sessions[id].close();
            }
            if (countBased) {
                rollbackCount(id);
            }
            failed += commitUris[id].size();
            commitUris[id].clear();
            throw new IOException(e);
        }
    }

    protected void insertContent(Content content, int id) 
    throws IOException {
        int t = 0;
        final int maxRetries = 15;
        int sleepTime = 100;
        final int maxSleepTime = 60000;
        while (t++ < maxRetries) {
            try {
                if (t > 1) {
                    LOG.info("Retrying document insert " + t);
                }
                sessions[id].insertContent(content);
                if (!needCommit) {
                    succeeded++;
                    pendingUris[id].clear();
                }
            } catch (RetryableQueryException e) {
                // log error and continue on RequestServerException
                LOG.error(e.getFormatString());

                if (needCommit && !commitUris[id].isEmpty()) {
                    rollback(id);
                }

                if (t < maxRetries) {
                    if (sessions[id] != null) {
                       sessions[id].close();
                    }
                    try {
                        Thread.sleep(sleepTime);
                    } catch (Exception e2) {
                    }
                    sleepTime = sleepTime * 2;
                    if (sleepTime > maxSleepTime)
                        sleepTime = maxSleepTime;

                    sessions[id] = getSession(id, false);
                    continue;
                } else {
                    failed++;
                    // remove the failed content from pendingUris
                    DocumentURI failedUri = pendingUris[id].remove(content.getUri());
                    LOG.warn("Failed document " + failedUri);
                }
            } catch (RequestServerException e) {
                if (e instanceof XQueryException) {
                    LOG.error(((XQueryException)e).getFormatString());
                } else {
                    LOG.error(e.getMessage());
                }
                failed++;   
                // remove the failed content from pendingUris
                DocumentURI failedUri = pendingUris[id].remove(content.getUri());
                LOG.warn("Failed document " + failedUri);
            } catch (RequestException e) {
                if (needCommit && !commitUris[id].isEmpty()) {
                    failed += commitUris[id].size();
                    for (DocumentURI failedUri : commitUris[id]) {
                        LOG.warn("Failed document: " + failedUri);
                    }
                    commitUris[id].clear();
                }
                if (t < maxRetries) {
                    sessions[id] = getSession(id, true);
                    continue;
                }

                if (countBased) {
                    rollbackCount(id);
                }
                pendingUris[id].clear();
                throw new IOException(e);
            }
            break;
        }
    }
    
    protected void commit(int id) throws IOException {
        try {
            sessions[id].commit();
            succeeded += commitUris[id].size();
            commitUris[id].clear();
        } catch (RequestServerException e) {
            LOG.error("Error commiting transaction ", e);
            failed += commitUris[id].size();   
            for (DocumentURI failedUri : commitUris[id]) {
                LOG.warn("Failed document: " + failedUri);
            }
            commitUris[id].clear();
            if (sessions[id] != null) {
                sessions[id].close();
                sessions[id] = null;
            }
        } catch (RequestException e) {
            if (sessions[id] != null) {
                sessions[id].close();
            }
            if (countBased) {
                rollbackCount(id);
            }
            failed += commitUris[id].size();
            commitUris[id].clear();
            throw new IOException(e);
        } catch (Exception e) {
            LOG.error("Error commiting transaction ", e);
            if (sessions[id] != null) {
                sessions[id].close();
                sessions[id] = getSession(id, true);
            }
            failed += commitUris[id].size();
            for (DocumentURI failedUri : commitUris[id]) {
                LOG.warn("Failed document: " + failedUri);
            }
            commitUris[id].clear();
        }
    }
    
    @Override
    public void write(DocumentURI key, VALUEOUT value) 
    throws IOException, InterruptedException {
        InternalUtilities.getUriWithOutputDir(key, outputDir);
        int fId = 0;
        if (fastLoad) {
            if(!countBased) {
                // placement for legacy or bucket
                fId = am.getPlacementForestIndex(key);
                sfId = fId;
            } else {
                if (sfId == -1) {
                    sfId = am.getPlacementForestIndex(key);
                }
                fId = sfId;
            }
        } 
        int sid = fId;
        Content content = createContent(key,value); 
        if (content == null) {
            failed++;
            return;
        }
        if (countBased) {
            fId = 0;
        }
        pendingUris[sid].put(content, (DocumentURI)key.clone());
        boolean inserted = false;
        if (batchSize > 1) {
            forestContents[fId][counts[fId]++] = content;
            if (counts[fId] == batchSize) {
                if (sessions[sid] == null) {
                    sessions[sid] = getSession(sid, false);
                }  
                insertBatch(forestContents[fId], sid);
                stmtCounts[sid]++;
                
                //reset forest index for statistical
                if (countBased) {
                    sfId = -1;
                }
                counts[fId] = 0;
                inserted = true;
            }
        } else { // batchSize == 1
            if (sessions[sid] == null) {
                sessions[sid] = getSession(sid, false);
            }
            insertContent(content, sid);   
            stmtCounts[sid]++;
            //reset forest index for statistical
            if (countBased) {
                sfId = -1;
            }
            inserted = true;
        }
        boolean committed = false;
        if (needCommit && stmtCounts[sid] == txnSize) {
            commit(sid);  
            stmtCounts[sid] = 0;         
            committed = true;
        }
        if ((!fastLoad) && ((inserted && (!needCommit)) || committed)) { 
            // rotate to next host and reset session
            int oldHostId = hostId;
            for (;;) {
                hostId = (hostId + 1)%forestIds.length;
                if (!blacklist[hostId]) {
                  break;
                }
                if (hostId == oldHostId) {
                    for (int i = 0; i < blacklist.length; i++) {
                        blacklist[i] = false;
                    }
                }
            }
            sessions[0] = null;
        }
    }

    protected void rollbackCount(int fId) {
        ((StatisticalAssignmentPolicy)am.getPolicy()).updateStats(fId, 
                -batchSize);
    }
    
    protected Session getSession(int fId, boolean nextReplica, TransactionMode mode) {
        Session session = null;
        if (fastLoad) {
            List<ForestHost> replicas = am.getReplicas(forestIds[fId]);

            if (nextReplica) {
                curReplica[fId] = (curReplica[fId] + 1)%replicas.size();
            }

            ContentSource cs = hostSourceMap.get(replicas.get(curReplica[fId]).getHostName());
            String forestId = replicas.get(curReplica[fId]).getForest();
            session = cs.newSession(ID_PREFIX + forestId);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Connect to forest " + forestId + " on "
                    + session.getConnectionUri().getHost());
            }
        } else {
            if (nextReplica) {
                blacklist[hostId] = true;
                int oldHostId = hostId;
                for (;;) {
                    hostId = (hostId + 1)%forestIds.length;
                    if (!blacklist[hostId])
                        break;
                    if (hostId == oldHostId) {
                        for (int i = 0; i < blacklist.length; i++) {
                            blacklist[i] = false;
                        }
                    }
                }
            }
            String forestId = forestIds[hostId];
            ContentSource cs = hostSourceMap.get(forestId);
            session = cs.newSession();
            if (LOG.isDebugEnabled()) {
                LOG.debug("Connect to " + session.getConnectionUri().getHost());
            }
        }      
        session.setTransactionMode(mode);
        session.setDefaultRequestOptions(requestOptions);
        ((SessionImpl)session).setCompatibleMode(isTxnCompatible);
        return session;
    }
    
    protected Session getSession(int fId, boolean nextReplica) {
        TransactionMode mode = TransactionMode.AUTO;
        if (needCommit) {
            mode = TransactionMode.UPDATE;
        }
        return getSession(fId, nextReplica, mode);
    }

    @Override
    public void close(TaskAttemptContext context) throws IOException,
    InterruptedException {
        if (batchSize > 1) {
            int len, sid;
            if (countBased) {
                len = 1;
                sid = sfId;
            } else {
            	len = fastLoad ? forestIds.length : 1;
            	sid = 0;
            }
            for (int i = 0; i < len; i++, sid++) {             
                if (counts[i] > 0) {
                    Content[] remainder = new Content[counts[i]];
                    System.arraycopy(forestContents[i], 0, remainder, 0, 
                            counts[i]);
                    if (sessions[sid] == null) {
                        sessions[sid] = getSession(sid, false);
                    }
                    try {
                        insertBatch(remainder, sid);
                    } catch (Throwable e) {
                        LOG.error("Error caught inserting documents: ", e);
                    }
                    stmtCounts[sid]++;
                }
            }
        }
        for (int i = 0; i < sessions.length; i++) {
            if (sessions[i] != null) {
                if (stmtCounts[i] > 0 && needCommit) {
                    try {
                        commit(i);
                    } catch (Throwable e) {
                        LOG.error("Error committing transaction: ", e);
                    }
                }
                sessions[i].close();
            }
        }
        if (is != null) {
            is.close();
            if (is instanceof ZipEntryInputStream) {
                ((ZipEntryInputStream)is).closeZipInputStream();
            }
        }
        Counter committedCounter = context.getCounter(
                MarkLogicCounter.OUTPUT_RECORDS_COMMITTED);
        synchronized(committedCounter) {
            committedCounter.increment(succeeded);
        }
        Counter failedCounter = context.getCounter(
                MarkLogicCounter.OUTPUT_RECORDS_FAILED);
        synchronized(failedCounter) {
            failedCounter.increment(failed);
        }
    }
    
    @Override
    public int getTransactionSize(Configuration conf) {
        // return the specified txn size
        if (conf.get(TXN_SIZE) != null) {
            int txnSize = conf.getInt(TXN_SIZE, 0);
            return txnSize <= 0 ? 1 : txnSize;
        } 
        return 1000 / conf.getInt(BATCH_SIZE, DEFAULT_BATCH_SIZE);
    }
    
}
