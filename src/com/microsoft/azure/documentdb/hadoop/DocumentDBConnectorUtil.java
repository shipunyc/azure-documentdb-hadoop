//------------------------------------------------------------
// Copyright (c) Microsoft Corporation.  All rights reserved.
//------------------------------------------------------------

package com.microsoft.azure.documentdb.hadoop;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.microsoft.azure.documentdb.Database;
import com.microsoft.azure.documentdb.Document;
import com.microsoft.azure.documentdb.DocumentClient;
import com.microsoft.azure.documentdb.DocumentClientException;
import com.microsoft.azure.documentdb.DocumentCollection;
import com.microsoft.azure.documentdb.IndexType;
import com.microsoft.azure.documentdb.IndexingPath;
import com.microsoft.azure.documentdb.IndexingPolicy;
import com.microsoft.azure.documentdb.QueryIterable;
import com.microsoft.azure.documentdb.RequestOptions;
import com.microsoft.azure.documentdb.SqlParameter;
import com.microsoft.azure.documentdb.SqlParameterCollection;
import com.microsoft.azure.documentdb.SqlQuerySpec;
import com.microsoft.azure.documentdb.StoredProcedure;

/**
 * 
 * Utils used by the connector for DocumentDBCrud
 *
 */
public class DocumentDBConnectorUtil {
    private static final Log LOG = LogFactory.getLog(DocumentDBConnectorUtil.class);
    private final static int MAX_SCRIPT_DOCS = 50;
    private final static int MAX_SCRIPT_SIZE = 50000;
    private final static String BULK_IMPORT_ID = "HadoopBulkImportSprocV1";
    private final static String BULK_IMPORT_PATH = "/BulkImportScript.js";
    
    public static String UserAgentSuffix = " HadoopConnector/1.0.0";
    
    /**
     * Gets an output collection with the passed name ( if the collection already exists return it, otherwise create new one
     * @param client The DocumentClient instance.
     * @param databaseSelfLink the self link of the passed database.
     * @param collectionName The id of the output collection.
     * @param rangeIndexes An optional parameter that contain index paths for range indexes and it will be used to create an indexing policy.
     */
    public static DocumentCollection getOrCreateOutputCollection(DocumentClient client, String databaseSelfLink,
            String collectionName, String[] rangeIndexes, String offerType) throws DocumentClientException {
        
        DocumentCollection outputCollection = DocumentDBConnectorUtil.GetDocumentCollection(client, databaseSelfLink, collectionName);
        
        if (outputCollection == null) {
            DocumentCollection outputColl = new DocumentCollection("{ 'id':'" + collectionName + "' }");
            if (rangeIndexes != null && rangeIndexes.length > 0) {
                IndexingPolicy policy = new IndexingPolicy();
                ArrayList<IndexingPath> indexingPaths = new ArrayList<IndexingPath>(rangeIndexes.length);
                for (int i = 0; i < rangeIndexes.length; i++) {
                    IndexingPath path = new IndexingPath();
                    path.setIndexType(IndexType.Range);
                    path.setPath(rangeIndexes[i]);
                    indexingPaths.add(path);
                }

                IndexingPath defaultPath = new IndexingPath();
                defaultPath.setPath("/");
                indexingPaths.add(defaultPath);
                policy.getIncludedPaths().addAll(indexingPaths);
                outputColl.setIndexingPolicy(policy);
            }

            BackoffExponentialRetryPolicy retryPolicy = new BackoffExponentialRetryPolicy();
            
            while(retryPolicy.shouldRetry()) {
                try {
                    RequestOptions options = new RequestOptions();
                    options.setOfferType(offerType);
                    outputCollection = client.createCollection(databaseSelfLink, outputColl, options).getResource();
                    break;
                } catch (Exception e) {
                    retryPolicy.errorOccured(e);
                }
            }
        }
        
        return outputCollection;
    }
    
    public static DocumentCollection GetDocumentCollection(DocumentClient client, String databaseSelfLink, String collectionId) {
        BackoffExponentialRetryPolicy retryPolicy = new BackoffExponentialRetryPolicy();
        QueryIterable<DocumentCollection> collIterable = client.queryCollections(
                databaseSelfLink,
                new SqlQuerySpec("SELECT * FROM root r WHERE r.id=@id",
                        new SqlParameterCollection(new SqlParameter("@id", collectionId))),
                null).getQueryIterable();
        
        List<DocumentCollection> collections = null;
        while(retryPolicy.shouldRetry()){
            try {
                collections = collIterable.toList();
                break;
            } catch (Exception e) {
                retryPolicy.errorOccured(e);
            }
        }
        
        if(collections.size() == 0) {
            return null;
        }
        
        return collections.get(0);
    }
    
    public static Database GetDatabase(DocumentClient client, String databaseId) {
        BackoffExponentialRetryPolicy retryPolicy = new BackoffExponentialRetryPolicy();
        QueryIterable<Database> dbIterable = client.queryDatabases(
                new SqlQuerySpec("SELECT * FROM root r WHERE r.id=@id",
                        new SqlParameterCollection(new SqlParameter("@id", databaseId))),
                null).getQueryIterable();
        
        List<Database> databases = null;
        while(retryPolicy.shouldRetry()){
            try {
                databases = dbIterable.toList();
                break;
            } catch (Exception e) {
                retryPolicy.errorOccured(e);
            }
        }
        
        if(databases.size() == 0) {
            return null;
        }
        
        return databases.get(0);
    }
    
    /**
     * Gets the bulk import stored procedure that will be used for writing documents ( if the sproc already exists, use it, otherwise create a new one.
     * @param client the DocumentClient instance for DocumentDB.
     * @param collectionLink the self-link of the collection to write to.
     * @return StoredProcedure instance that will be used for writing
     */
    public static StoredProcedure CreateBulkImportStoredProcedure(DocumentClient client, String collectionLink)
            throws DocumentClientException {
        BackoffExponentialRetryPolicy retryPolicy = new BackoffExponentialRetryPolicy();
        List<StoredProcedure> sprocs = null;
        
        while(retryPolicy.shouldRetry()){
            try {
                sprocs = client.queryStoredProcedures(collectionLink,
                        new SqlQuerySpec("SELECT * FROM root r WHERE r.id=@id",
                                new SqlParameterCollection(new SqlParameter("@id", BULK_IMPORT_ID))),
                        null).getQueryIterable().toList();
                break;
            } catch (Exception e) {
                retryPolicy.errorOccured(e);
            }
        }
        
        if(sprocs.size() > 0) {
            return sprocs.get(0);
        }
        
        StoredProcedure sproc = new StoredProcedure();
        sproc.setId(BULK_IMPORT_ID);
        String sprocBody = getBulkImportBody(client);
        sproc.setBody(sprocBody);
        return client.createStoredProcedure(collectionLink, sproc, null).getResource();
    }

    /**
     * Executes the bulk import stored procedure for a list of documents.
     * The execution takes into consideration throttling and blacklisting of the stored procedure.
     * @param client The DocumentClient instance for DocumentDB
     * @param collectionSelfLink the self-link for the collection to write to.
     * @param sproc The stored procedure to execute
     * @param allDocs The list of documents to write
     * @param upsert  Specifies whether to replace the document if exists or not. By default it's true.
     */
    public static void executeWriteStoredProcedure(final DocumentClient client, String collectionSelfLink, final StoredProcedure sproc,
            List<Document> allDocs, final boolean upsert) {
        
        int currentCount = 0;
            
        while (currentCount < allDocs.size())
            {
            String []jsonArrayString = CreateBulkInsertScriptArguments(allDocs, currentCount, MAX_SCRIPT_SIZE);
            BackoffExponentialRetryPolicy retryPolicy = new BackoffExponentialRetryPolicy();
            String response = null;
            while(retryPolicy.shouldRetry()){
                try {
                    response = client.executeStoredProcedure(sproc.getSelfLink(), new Object[] { jsonArrayString, upsert })
                        .getResponseAsString();
                    break;
                } catch(Exception e){
                    retryPolicy.errorOccured(e);  
                }
            }
    
            int createdCount = Integer.parseInt(response);
            currentCount += createdCount;
        }
    }

    /**
     * 
     * @param docs The list of documents to be created 
     * @param currentIndex the current index in the list of docs to start with.
     * @param maxCount the max count to be created by the sproc.
     * @param maxScriptSize the max size of the sproc that is used to avoid exceeding the max request size.
     * @return a string array for all documents to be created
     */
    private static String[] CreateBulkInsertScriptArguments(List<Document> docs, int currentIndex, int maxScriptSize)
    {
        if (currentIndex >= docs.size()) return new String[]{};
        
        ArrayList<String> jsonDocumentList = new ArrayList<String>();
        String stringifiedDoc;
        int scriptCapacityRemaining = maxScriptSize;

        int i = 0;
        while (scriptCapacityRemaining > 0 && i < MAX_SCRIPT_DOCS && currentIndex + i < docs.size())
        {
            stringifiedDoc = docs.get(currentIndex + i).toString();
            jsonDocumentList.add(stringifiedDoc);
            scriptCapacityRemaining-= stringifiedDoc.length();
            i++;
        }

        String[] jsonDocumentArray = new String[jsonDocumentList.size()];
        jsonDocumentList.toArray(jsonDocumentArray);
        return jsonDocumentArray;
    }
    
    /**
     * Reads the bulk import script body from the file.
     * @param client the DocumentClient instance.
     * @return a string that contains the stored procedure body.
     */
    private static String getBulkImportBody(DocumentClient client) {
        try {
            InputStream stream = DocumentDBConnectorUtil.class.getResourceAsStream(BULK_IMPORT_PATH);
            List<String> scriptLines = IOUtils.readLines(stream);
            StringBuilder scriptBody = new StringBuilder();
            for (Iterator<String> iterator = scriptLines.iterator(); iterator.hasNext();) {
                String line = (String) iterator.next();
                scriptBody.append(line + "\n");
            }

            return scriptBody.toString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * If no id is provided, replace it with an auto generated guid id.
     * @param doc The document to be checked for id.
     */
    public static void addIdIfMissing(Document doc) {
        if (doc.getId() == null) {
            doc.setId(UUID.randomUUID().toString());
        }
    }
}
