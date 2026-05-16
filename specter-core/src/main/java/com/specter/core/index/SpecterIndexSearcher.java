package com.specter.core.index;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;

import java.io.IOException;
import java.util.*;

import lombok.extern.slf4j.Slf4j;

/**
 * High-speed fuzzy query service that returns absolute node IDs in milliseconds.
 * Runs against the RAM-based Lucene index to prevent MCP tools from executing
 * blind depth-first graph traversals for simple component lookups.
 */
@Slf4j
public class SpecterIndexSearcher {

    private final Directory indexDirectory;

    public SpecterIndexSearcher(Directory indexDirectory) {
        this.indexDirectory = indexDirectory;
    }

    /**
     * Executes a fuzzy text search across node names, types, and metadata.
     * Returns matching node IDs sorted by relevance score (descending).
     */
    public List<SearchHit> search(String queryText, int maxResults) {
        List<SearchHit> results = new ArrayList<>();

        try (var reader = DirectoryReader.open(indexDirectory)) {
            IndexSearcher searcher = new IndexSearcher(reader);

            String[] fields = {"type^4", "fullText", "name^2", "metadata"};
            MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, new StandardAnalyzer());
            parser.setDefaultOperator(QueryParser.Operator.OR);
            parser.setFuzzyMinSim(2); // Enable fuzzy matching with edit distance 2

            Query query = parser.parse(queryText);
            TopDocs topDocs = searcher.search(query, maxResults);

            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                var doc = searcher.storedFields().document(scoreDoc.doc);
                String nodeId = doc.get("nodeId");
                String name = doc.get("name");
                String type = doc.get("type");

                if (nodeId != null) {
                    results.add(new SearchHit(nodeId, name, type, scoreDoc.score));
                }
            }
        } catch (Exception e) {
            log.warn("Search failed for query '{}': {}", queryText, e.getMessage());
        }

        return results;
    }

    /**
     * Finds nodes by exact type match (e.g., all CASSANDRA_TABLE nodes).
     */
    public List<SearchHit> findByType(String nodeType, int maxResults) {
        List<SearchHit> results = new ArrayList<>();

        try (var reader = DirectoryReader.open(indexDirectory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            Query query = new TermQuery(new Term("type", nodeType));
            TopDocs topDocs = searcher.search(query, maxResults);

            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                var doc = searcher.storedFields().document(scoreDoc.doc);
                String nodeId = doc.get("nodeId");
                String name = doc.get("name");
                String type = doc.get("type");

                if (nodeId != null) {
                    results.add(new SearchHit(nodeId, name, type, scoreDoc.score));
                }
            }
        } catch (IOException e) {
            log.warn("Type search failed for '{}': {}", nodeType, e.getMessage());
        }

        return results;
    }

    /**
     * Returns edges by type. Useful for finding all PUBLISHES_TO relationships.
     */
    public List<EdgeHit> findEdgesByType(String edgeType, int maxResults) {
        List<EdgeHit> results = new ArrayList<>();

        try (var reader = DirectoryReader.open(indexDirectory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            Query query = new TermQuery(new Term("edgeType", edgeType));
            TopDocs topDocs = searcher.search(query, maxResults);

            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                var doc = searcher.storedFields().document(scoreDoc.doc);
                String sourceId = doc.get("edgeSourceId");
                String targetId = doc.get("edgeTargetId");
                String type = doc.get("edgeType");

                if (sourceId != null && targetId != null) {
                    results.add(new EdgeHit(sourceId, targetId, type));
                }
            }
        } catch (IOException e) {
            log.warn("Edge search failed for '{}': {}", edgeType, e.getMessage());
        }

        return results;
    }

    public record SearchHit(String nodeId, String name, String type, float score) {}

    public record EdgeHit(String sourceId, String targetId, String type) {}
}
