/*
 * This file is part of Dependency-Track.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) OWASP Foundation. All Rights Reserved.
 */
package org.dependencytrack.search;

import alpine.common.logging.Logger;
import alpine.notification.Notification;
import alpine.notification.NotificationLevel;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.Term;
import org.dependencytrack.model.VulnerableSoftware;
import org.dependencytrack.notification.NotificationConstants;
import org.dependencytrack.notification.NotificationGroup;
import org.dependencytrack.notification.NotificationScope;
import org.dependencytrack.persistence.QueryManager;
import org.dependencytrack.search.document.VulnerableSoftwareDocument;

import javax.jdo.Query;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Indexer for operating on VulnerableSoftware.
 *
 * @author Steve Springett
 * @since 3.6.0
 */
public final class VulnerableSoftwareIndexer extends IndexManager implements ObjectIndexer<VulnerableSoftwareDocument> {

    private static final Logger LOGGER = Logger.getLogger(VulnerableSoftwareIndexer.class);
    private static final VulnerableSoftwareIndexer INSTANCE = new VulnerableSoftwareIndexer();

    protected static VulnerableSoftwareIndexer getInstance() {
        return INSTANCE;
    }

    /**
     * Private constructor.
     */
    private VulnerableSoftwareIndexer() {
        super(IndexType.VULNERABLESOFTWARE);
    }

    @Override
    public String[] getSearchFields() {
        return IndexConstants.VULNERABLESOFTWARE_SEARCH_FIELDS;
    }

    /**
     * Adds a VulnerableSoftware object to a Lucene index.
     *
     * @param vs A persisted VulnerableSoftware object.
     */
    public void add(final VulnerableSoftwareDocument vs) {
        final Document doc = new Document();
        addField(doc, IndexConstants.VULNERABLESOFTWARE_UUID, vs.uuid().toString(), Field.Store.YES, false);
        addField(doc, IndexConstants.VULNERABLESOFTWARE_CPE_22, vs.cpe22(), Field.Store.YES, false);
        addField(doc, IndexConstants.VULNERABLESOFTWARE_CPE_23, vs.cpe23(), Field.Store.YES, false);
        addField(doc, IndexConstants.VULNERABLESOFTWARE_VENDOR, vs.vendor(), Field.Store.YES, true);
        addField(doc, IndexConstants.VULNERABLESOFTWARE_PRODUCT, vs.product(), Field.Store.YES, true);
        addField(doc, IndexConstants.VULNERABLESOFTWARE_VERSION, vs.version(), Field.Store.YES, true);
        //todo: index the affected version range fields as well

        try {
            getIndexWriter().addDocument(doc);
        } catch (CorruptIndexException e) {
            handleCorruptIndexException(e);
        } catch (IOException e) {
            LOGGER.error("An error occurred while adding a VulnerableSoftware to the index", e);
            Notification.dispatch(new Notification()
                    .scope(NotificationScope.SYSTEM)
                    .group(NotificationGroup.INDEXING_SERVICE)
                    .title(NotificationConstants.Title.VULNERABLESOFTWARE_INDEXER)
                    .content("An error occurred while adding a VulnerableSoftware to the index. Check log for details. " + e.getMessage())
                    .level(NotificationLevel.ERROR)
            );
        }
    }

    /**
     * Deletes a VulnerableSoftware object from the Lucene index.
     *
     * @param vs A persisted VulnerableSoftware object.
     */
    public void remove(final VulnerableSoftwareDocument vs) {
        try {
            getIndexWriter().deleteDocuments(new Term(IndexConstants.VULNERABLESOFTWARE_UUID, vs.uuid().toString()));
        } catch (CorruptIndexException e) {
            handleCorruptIndexException(e);
        } catch (IOException e) {
            LOGGER.error("An error occurred while removing a VulnerableSoftware from the index", e);
            Notification.dispatch(new Notification()
                    .scope(NotificationScope.SYSTEM)
                    .group(NotificationGroup.INDEXING_SERVICE)
                    .title(NotificationConstants.Title.VULNERABLESOFTWARE_INDEXER)
                    .content("An error occurred while removing a VulnerableSoftware from the index. Check log for details. " + e.getMessage())
                    .level(NotificationLevel.ERROR)
            );
        }
    }

    /**
     * Re-indexes all VulnerableSoftware objects.
     */
    public void reindex() {
        LOGGER.info("Starting reindex task. This may take some time.");
        super.reindex();

        long indexedDocs = 0;
        final long startTimeNs = System.nanoTime();
        try (QueryManager qm = new QueryManager()) {
            List<VulnerableSoftwareDocument> docs = fetchNext(qm, null);
            while (!docs.isEmpty()) {
                docs.forEach(this::add);
                indexedDocs += docs.size();
                commit();

                docs = fetchNext(qm, docs.get(docs.size() - 1).id());
            }
        }
        LOGGER.info("Reindexing of %d VulnerableSoftwares completed in %s"
                .formatted(indexedDocs, Duration.ofNanos(System.nanoTime() - startTimeNs)));
    }

    private static List<VulnerableSoftwareDocument> fetchNext(final QueryManager qm, final Long lastId) {
        final Query<VulnerableSoftware> query = qm.getPersistenceManager().newQuery(VulnerableSoftware.class);
        if (lastId != null) {
            query.setFilter("id > :lastId");
            query.setParameters(lastId);
        }
        query.setOrdering("id ASC");
        query.setRange(0, 1000);
        query.setResult("id, uuid, cpe22, cpe23, vendor, product, version");
        try {
            return List.copyOf(query.executeResultList(VulnerableSoftwareDocument.class));
        } finally {
            query.closeAll();
        }
    }

}
