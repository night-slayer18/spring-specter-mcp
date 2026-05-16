package com.specter.core.index;

import com.specter.core.graph.SpecterEdge;
import com.specter.core.graph.SpecterNode;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import java.io.IOException;
import java.util.Collection;

import lombok.extern.slf4j.Slf4j;

/**
 * Indexes every resolved {@link SpecterNode} — class names, type, metadata, and
 * descriptions — into an in-memory RAM-based Lucene directory for sub-millisecond
 * fuzzy search.
 */
@Slf4j
public class SpecterIndexWriter {

    private final Directory indexDirectory;
    private final IndexWriter writer;

    public SpecterIndexWriter() throws IOException {
        this.indexDirectory = new ByteBuffersDirectory();
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        this.writer = new IndexWriter(indexDirectory, config);
    }

    public void indexNode(SpecterNode node) {
        try {
            Document doc = new Document();

            doc.add(new StringField("nodeId", node.id(), Field.Store.YES));
            doc.add(new TextField("name", node.name(), Field.Store.YES));
            doc.add(new StringField("type", node.type().name(), Field.Store.YES));

            // Flatten metadata into a searchable text field
            StringBuilder metaText = new StringBuilder();
            node.metadata().forEach((key, value) -> {
                doc.add(new StringField("meta_" + key, value, Field.Store.YES));
                metaText.append(value).append(" ");
            });
            doc.add(new TextField("metadata", metaText.toString(), Field.Store.NO));

            // Combined full-text field for fuzzy search
            doc.add(new TextField("fullText",
                    node.name() + " " + node.type().name() + " " + metaText,
                    Field.Store.NO));

            writer.addDocument(doc);
        } catch (IOException e) {
            log.warn("Failed to index node: {}", node.id(), e);
        }
    }

    public void indexNodes(Collection<SpecterNode> nodes) {
        nodes.forEach(this::indexNode);
    }

    public void indexEdge(SpecterEdge edge) {
        try {
            Document doc = new Document();
            doc.add(new StringField("edgeSourceId", edge.sourceId(), Field.Store.YES));
            doc.add(new StringField("edgeTargetId", edge.targetId(), Field.Store.YES));
            doc.add(new StringField("edgeType", edge.type().name(), Field.Store.YES));
            writer.addDocument(doc);
        } catch (IOException e) {
            log.warn("Failed to index edge: {} -> {}", edge.sourceId(), edge.targetId(), e);
        }
    }

    /**
     * Wipes the index so a fresh rebuild can begin. Call this before re-indexing
     * on incremental analysis or profile changes to avoid duplicate documents.
     */
    public void clearIndex() throws IOException {
        writer.deleteAll();
        writer.commit();
        log.debug("Lucene index cleared");
    }

    public void commit() {
        try {
            writer.commit();
            log.info("Lucene index committed: {} documents", writer.getDocStats().numDocs);
        } catch (IOException e) {
            log.error("Failed to commit Lucene index", e);
        }
    }

    public Directory getDirectory() {
        return indexDirectory;
    }

    public void close() {
        try {
            writer.close();
            indexDirectory.close();
        } catch (IOException e) {
            log.warn("Error closing Lucene index", e);
        }
    }
}
