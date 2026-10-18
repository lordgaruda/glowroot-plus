/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.central.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import org.glowroot.central.util.Session;
import org.glowroot.common2.ai.InsightsRepository;
import org.glowroot.common2.repo.CassandraProfile;

/** Agent partition + fingerprint clustering key; TTL also bounds candidate state. */
public final class InsightsDao implements InsightsRepository {
    private final Session session;
    private final PreparedStatement read, list, insert, update;
    public InsightsDao(Session session) throws Exception {
        this.session = session;
        session.createTableWithLCS("create table if not exists ai_insight (agent varchar,"
                + " id varchar, revision varchar, document varchar, primary key (agent, id))");
        read = session.prepare("select revision, document from ai_insight where agent=? and id=?");
        list = session.prepare("select revision, document from ai_insight where agent=? limit 1000");
        insert = session.prepare("insert into ai_insight (agent,id,revision,document)"
                + " values (?,?,?,?) if not exists using ttl ?");
        update = session.prepare("update ai_insight using ttl ? set revision=?,document=?"
                + " where agent=? and id=? if revision=?");
    }
    public Document read(String agent, String id) {
        Row row = session.read(read.bind(agent, id), CassandraProfile.web).one();
        return row == null ? null : new Document(row.getString(0), row.getString(1));
    }
    public List<Document> list(String agent) {
        List<Document> result = new ArrayList<>();
        for (Row row : session.read(list.bind(agent), CassandraProfile.web)) {
            result.add(new Document(row.getString(0), row.getString(1)));
        }
        return result;
    }
    public boolean compareAndSet(String agent, String id, String revision, String json, int ttl)
            throws Exception {
        String next = UUID.randomUUID().toString();
        return session.update(revision == null ? insert.bind(agent, id, next, json, ttl)
                : update.bind(ttl, next, json, agent, id, revision), CassandraProfile.collector)
                .wasApplied();
    }
}
