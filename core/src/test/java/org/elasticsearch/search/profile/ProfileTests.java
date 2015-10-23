/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.profile;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.RandomIndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.RandomApproximationQuery;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TotalHitCountCollector;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.TestUtil;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.search.internal.ContextIndexSearcher;
import org.elasticsearch.test.ESTestCase;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

public class ProfileTests extends ESTestCase {

    static Directory dir;
    static IndexReader reader;
    static ContextIndexSearcher searcher;

    @BeforeClass
    public static void before() throws IOException {
        dir = newDirectory();
        RandomIndexWriter w = new RandomIndexWriter(random(), dir);
        final int numDocs = TestUtil.nextInt(random(), 1, 20);
        for (int i = 0; i < numDocs; ++i) {
            final int numHoles = random().nextInt(5);
            for (int j = 0; j < numHoles; ++j) {
                w.addDocument(new Document());
            }
            Document doc = new Document();
            doc.add(new StringField("foo", "bar", Store.NO));
            w.addDocument(doc);
        }
        reader = w.getReader();
        w.close();
        Engine.Searcher engineSearcher = new Engine.Searcher("test", new IndexSearcher(reader));
        searcher = new ContextIndexSearcher(engineSearcher, IndexSearcher.getDefaultQueryCache(), MAYBE_CACHE_POLICY);
    }

    @AfterClass
    public static void after() throws IOException {
        IOUtils.close(reader, dir);
        dir = null;
        reader = null;
        searcher = null;
    }

    public void testBasic() throws IOException {
        Profiler profiler = new Profiler();
        searcher.setProfiler(profiler);
        Query query = new TermQuery(new Term("foo", "bar"));
        searcher.search(query, 1);
        List<InternalProfileResult> results = profiler.getQueryTree();
        assertEquals(1, results.size());
        Map<String, Long> breakdown = results.get(0).getTimeBreakdown();
        assertThat(breakdown.get(ProfileBreakdown.TimingType.REWRITE.toString()).longValue(), greaterThan(0L));
        assertThat(breakdown.get(ProfileBreakdown.TimingType.WEIGHT.toString()).longValue(), greaterThan(0L));
        assertThat(breakdown.get(ProfileBreakdown.TimingType.BUILD_SCORER.toString()).longValue(), greaterThan(0L));
        assertThat(breakdown.get(ProfileBreakdown.TimingType.NEXT_DOC.toString()).longValue(), greaterThan(0L));
        assertThat(breakdown.get(ProfileBreakdown.TimingType.ADVANCE.toString()).longValue(), equalTo(0L));
        assertThat(breakdown.get(ProfileBreakdown.TimingType.SCORE.toString()).longValue(), greaterThan(0L));
        assertThat(breakdown.get(ProfileBreakdown.TimingType.MATCH.toString()).longValue(), equalTo(0L));
    }

    public void testNoScoring() throws IOException {
        Profiler profiler = new Profiler();
        searcher.setProfiler(profiler);
        Query query = new TermQuery(new Term("foo", "bar"));
        searcher.search(query, 1, Sort.INDEXORDER); // scores are not needed
        List<InternalProfileResult> results = profiler.getQueryTree();
        assertEquals(1, results.size());
        Map<String, Long> breakdown = results.get(0).getTimeBreakdown();
        assertThat(breakdown.get(ProfileBreakdown.TimingType.REWRITE.toString()).longValue(), greaterThan(0L));
        assertThat(breakdown.get(ProfileBreakdown.TimingType.WEIGHT.toString()).longValue(), greaterThan(0L));
        assertThat(breakdown.get(ProfileBreakdown.TimingType.BUILD_SCORER.toString()).longValue(), greaterThan(0L));
        assertThat(breakdown.get(ProfileBreakdown.TimingType.NEXT_DOC.toString()).longValue(), greaterThan(0L));
        assertThat(breakdown.get(ProfileBreakdown.TimingType.ADVANCE.toString()).longValue(), equalTo(0L));
        assertThat(breakdown.get(ProfileBreakdown.TimingType.SCORE.toString()).longValue(), equalTo(0L));
        assertThat(breakdown.get(ProfileBreakdown.TimingType.MATCH.toString()).longValue(), equalTo(0L));
    }

    public void testUseIndexStats() throws IOException {
        Profiler profiler = new Profiler();
        searcher.setProfiler(profiler);
        Query query = new TermQuery(new Term("foo", "bar"));
        searcher.count(query); // will use index stats
        List<InternalProfileResult> results = profiler.getQueryTree();
        assertEquals(1, results.size());
        Map<String, Long> breakdown = results.get(0).getTimeBreakdown();
        // nocommit: the below assertion should not fail
        //assertThat(breakdown.get(ProfileBreakdown.TimingType.REWRITE.toString()).longValue(), greaterThan(0L));
        assertThat(breakdown.get(ProfileBreakdown.TimingType.WEIGHT.toString()).longValue(), equalTo(0L));
        assertThat(breakdown.get(ProfileBreakdown.TimingType.BUILD_SCORER.toString()).longValue(), equalTo(0L));
        assertThat(breakdown.get(ProfileBreakdown.TimingType.NEXT_DOC.toString()).longValue(), equalTo(0L));
        assertThat(breakdown.get(ProfileBreakdown.TimingType.ADVANCE.toString()).longValue(), equalTo(0L));
        assertThat(breakdown.get(ProfileBreakdown.TimingType.SCORE.toString()).longValue(), equalTo(0L));
        assertThat(breakdown.get(ProfileBreakdown.TimingType.MATCH.toString()).longValue(), equalTo(0L));
    }

    public void testApproximations() throws IOException {
        Profiler profiler = new Profiler();
        Engine.Searcher engineSearcher = new Engine.Searcher("test", new IndexSearcher(reader));
        // disable query caching since we want to test approximations, which won't
        // be exposed on a cached entry
        ContextIndexSearcher searcher = new ContextIndexSearcher(engineSearcher, null, MAYBE_CACHE_POLICY);
        searcher.setProfiler(profiler);
        Query query = new RandomApproximationQuery(new TermQuery(new Term("foo", "bar")), random());
        searcher.count(query);
        List<InternalProfileResult> results = profiler.getQueryTree();
        assertEquals(1, results.size());
        Map<String, Long> breakdown = results.get(0).getTimeBreakdown();
        assertThat(breakdown.get(ProfileBreakdown.TimingType.REWRITE.toString()).longValue(), greaterThan(0L));
        assertThat(breakdown.get(ProfileBreakdown.TimingType.WEIGHT.toString()).longValue(), greaterThan(0L));
        assertThat(breakdown.get(ProfileBreakdown.TimingType.BUILD_SCORER.toString()).longValue(), greaterThan(0L));
        assertThat(breakdown.get(ProfileBreakdown.TimingType.NEXT_DOC.toString()).longValue(), greaterThan(0L));
        assertThat(breakdown.get(ProfileBreakdown.TimingType.ADVANCE.toString()).longValue(), equalTo(0L));
        assertThat(breakdown.get(ProfileBreakdown.TimingType.SCORE.toString()).longValue(), equalTo(0L));
        assertThat(breakdown.get(ProfileBreakdown.TimingType.MATCH.toString()).longValue(), greaterThan(0L));
    }

    public void testCollector() throws IOException {
        TotalHitCountCollector collector = new TotalHitCountCollector();
        ProfileCollector profileCollector = new ProfileCollector(collector);
        assertEquals(0, profileCollector.getTime());
        final LeafCollector leafCollector = profileCollector.getLeafCollector(reader.leaves().get(0));
        assertThat(profileCollector.getTime(), greaterThan(0L));
        long time = profileCollector.getTime();
        leafCollector.setScorer(Lucene.illegalScorer("dummy scorer"));
        assertThat(profileCollector.getTime(), greaterThan(time));
        time = profileCollector.getTime();
        leafCollector.collect(0);
        assertThat(profileCollector.getTime(), greaterThan(time));
    }
}
