/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.facet.sortedset;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.facet.DrillDownQuery;
import org.apache.lucene.facet.FacetResult;
import org.apache.lucene.facet.FacetTestCase;
import org.apache.lucene.facet.Facets;
import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.facet.FacetsCollectorManager;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.LabelAndValue;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.Accountable;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NamedThreadFactory;

public class TestSortedSetDocValuesFacets extends FacetTestCase {

  // NOTE: TestDrillSideways.testRandom also sometimes
  // randomly uses SortedSetDV

  public void testBasic() throws Exception {
    FacetsConfig config = new FacetsConfig();
    config.setMultiValued("a", true);
    config.setMultiValued("b", true);
    config.setRequireDimCount("b", true);
    try (Directory dir = newDirectory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir)) {
      Document doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("a", "foo"));
      doc.add(new SortedSetDocValuesFacetField("a", "bar"));
      doc.add(new SortedSetDocValuesFacetField("a", "zoo"));
      doc.add(new SortedSetDocValuesFacetField("b", "baz"));
      doc.add(new SortedSetDocValuesFacetField("b", "buzz"));
      writer.addDocument(config.build(doc));
      if (random().nextBoolean()) {
        writer.commit();
      }

      doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("a", "foo"));
      doc.add(new SortedSetDocValuesFacetField("b", "buzz"));
      writer.addDocument(config.build(doc));

      // NRT open
      try (IndexReader r = writer.getReader()) {
        IndexSearcher searcher = newSearcher(r);

        // Per-top-reader state:
        SortedSetDocValuesReaderState state =
            new DefaultSortedSetDocValuesReaderState(searcher.getIndexReader(), config);

        ExecutorService exec = randomExecutorServiceOrNull();
        try {
          Facets facets = getAllFacets(searcher, state, exec);

          // value for dim a should be -1 since it's multivalued but doesn't require dim counts:
          assertEquals(
              "dim=a path=[] value=-1 childCount=3\n  foo (2)\n  bar (1)\n  zoo (1)\n",
              facets.getTopChildren(10, "a").toString());
          // value for dim b should be 2 since it's multivalued but _does_ require dim counts:
          assertEquals(
              "dim=b path=[] value=2 childCount=2\n  buzz (2)\n  baz (1)\n",
              facets.getTopChildren(10, "b").toString());

          // DrillDown:
          DrillDownQuery q = new DrillDownQuery(config);
          q.add("a", "foo");
          q.add("b", "baz");
          TopDocs hits = searcher.search(q, 1);
          assertEquals(1, hits.totalHits.value);
        } finally {
          if (exec != null) exec.shutdownNow();
        }
      }
    }
  }

  public void testBasicHierarchical() throws Exception {
    FacetsConfig config = new FacetsConfig();
    config.setMultiValued("a", true);
    config.setRequireDimCount("a", true);
    config.setMultiValued("c", true);
    config.setHierarchical("c", true);
    try (Directory dir = newDirectory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir)) {
      Document doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("a", "foo"));
      doc.add(new SortedSetDocValuesFacetField("a", "bar"));
      doc.add(new SortedSetDocValuesFacetField("a", "zoo"));
      doc.add(new SortedSetDocValuesFacetField("b", "baz"));
      doc.add(new SortedSetDocValuesFacetField("c", "buzz"));
      doc.add(new SortedSetDocValuesFacetField("c", "buzz", "bee"));
      doc.add(new SortedSetDocValuesFacetField("c", "buzz", "bif"));
      doc.add(new SortedSetDocValuesFacetField("c", "buzz", "bif", "baf"));
      doc.add(new SortedSetDocValuesFacetField("c", "buzz", "biz"));
      doc.add(new SortedSetDocValuesFacetField("c", "buzz", "biz", "bar"));
      writer.addDocument(config.build(doc));
      if (random().nextBoolean()) {
        writer.commit();
      }

      doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("a", "foo"));
      doc.add(new SortedSetDocValuesFacetField("c", "buzz", "bif", "baf"));
      writer.addDocument(config.build(doc));

      // NRT open
      try (IndexReader r = writer.getReader()) {
        IndexSearcher searcher = newSearcher(r);

        // Per-top-reader state:
        SortedSetDocValuesReaderState state =
            new DefaultSortedSetDocValuesReaderState(searcher.getIndexReader(), config);

        ExecutorService exec = randomExecutorServiceOrNull();
        try {
          Facets facets = getAllFacets(searcher, state, exec);

          // since a is not set to be hierarchical but _is_ multi-valued, we expect a value of 2
          // (since two unique docs contain at least one value for this dim):
          assertEquals(
              "dim=a path=[] value=2 childCount=3\n  foo (2)\n  bar (1)\n  zoo (1)\n",
              facets.getTopChildren(10, "a").toString());
          assertEquals(
              "dim=b path=[] value=1 childCount=1\n  baz (1)\n",
              facets.getTopChildren(10, "b").toString());
          assertEquals(
              "dim=c path=[buzz] value=2 childCount=3\n  bif (2)\n  bee (1)\n  biz (1)\n",
              facets.getTopChildren(10, "c", "buzz").toString());
          assertEquals(
              "dim=c path=[buzz, bif] value=2 childCount=1\n  baf (2)\n",
              facets.getTopChildren(10, "c", "buzz", "bif").toString());

          // DrillDown:
          DrillDownQuery q = new DrillDownQuery(config);
          q.add("a", "foo");
          q.add("b", "baz");
          TopDocs hits = searcher.search(q, 1);
          assertEquals(1, hits.totalHits.value);

          q = new DrillDownQuery(config);
          q.add("c", "buzz", "bif");
          hits = searcher.search(q, 2);
          assertEquals(2, hits.totalHits.value);

          q = new DrillDownQuery(config);
          q.add("c", "buzz", "biz", "bar");
          hits = searcher.search(q, 2);
          assertEquals(1, hits.totalHits.value);
        } finally {
          if (exec != null) exec.shutdownNow();
        }
      }
    }
  }

  // See: LUCENE-10070
  public void testCountAll() throws Exception {
    try (Directory dir = newDirectory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir)) {
      FacetsConfig config = new FacetsConfig();

      Document doc = new Document();
      doc.add(new StringField("id", "0", Field.Store.NO));
      doc.add(new SortedSetDocValuesFacetField("a", "foo"));
      writer.addDocument(config.build(doc));

      doc = new Document();
      doc.add(new StringField("id", "1", Field.Store.NO));
      doc.add(new SortedSetDocValuesFacetField("a", "bar"));
      writer.addDocument(config.build(doc));

      writer.deleteDocuments(new Term("id", "0"));

      // NRT open
      try (IndexReader r = writer.getReader()) {
        IndexSearcher searcher = newSearcher(r);

        // Per-top-reader state:
        SortedSetDocValuesReaderState state =
            new DefaultSortedSetDocValuesReaderState(searcher.getIndexReader(), config);

        Facets facets = new SortedSetDocValuesFacetCounts(state);

        assertEquals(
            "dim=a path=[] value=1 childCount=1\n  bar (1)\n",
            facets.getTopChildren(10, "a").toString());

        ExecutorService exec =
            new ThreadPoolExecutor(
                1,
                TestUtil.nextInt(random(), 2, 6),
                Long.MAX_VALUE,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(),
                new NamedThreadFactory("TestIndexSearcher"));
        try {
          facets = new ConcurrentSortedSetDocValuesFacetCounts(state, exec);

          assertEquals(
              "dim=a path=[] value=1 childCount=1\n  bar (1)\n",
              facets.getTopChildren(10, "a").toString());
        } finally {
          exec.shutdownNow();
        }
      }
    }
  }

  public void testHierarchicalCountAll() throws Exception {
    try (Directory dir = newDirectory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir)) {
      FacetsConfig config = new FacetsConfig();
      config.setHierarchical("b", true);

      Document doc = new Document();
      doc.add(new StringField("id", "0", Field.Store.NO));
      doc.add(new SortedSetDocValuesFacetField("a", "foo"));
      doc.add(new SortedSetDocValuesFacetField("b", "buzz", "bee"));
      writer.addDocument(config.build(doc));

      doc = new Document();
      doc.add(new StringField("id", "1", Field.Store.NO));
      doc.add(new SortedSetDocValuesFacetField("a", "bar"));
      doc.add(new SortedSetDocValuesFacetField("b", "buzz", "baz"));
      writer.addDocument(config.build(doc));

      writer.deleteDocuments(new Term("id", "0"));

      // NRT open
      try (IndexReader r = writer.getReader()) {
        IndexSearcher searcher = newSearcher(r);

        // Per-top-reader state:
        SortedSetDocValuesReaderState state =
            new DefaultSortedSetDocValuesReaderState(searcher.getIndexReader(), config);

        Facets facets = new SortedSetDocValuesFacetCounts(state);

        assertEquals(
            "dim=a path=[] value=1 childCount=1\n  bar (1)\n",
            facets.getTopChildren(10, "a").toString());
        assertEquals(
            "dim=b path=[buzz] value=1 childCount=1\n  baz (1)\n",
            facets.getTopChildren(10, "b", "buzz").toString());

        ExecutorService exec =
            new ThreadPoolExecutor(
                1,
                TestUtil.nextInt(random(), 2, 6),
                Long.MAX_VALUE,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(),
                new NamedThreadFactory("TestIndexSearcher"));
        try {
          facets = new ConcurrentSortedSetDocValuesFacetCounts(state, exec);

          assertEquals(
              "dim=a path=[] value=1 childCount=1\n  bar (1)\n",
              facets.getTopChildren(10, "a").toString());
          assertEquals(
              "dim=b path=[buzz] value=1 childCount=1\n  baz (1)\n",
              facets.getTopChildren(10, "b", "buzz").toString());
        } finally {
          exec.shutdownNow();
        }
      }
    }
  }

  public void testBasicSingleValued() throws Exception {
    FacetsConfig config = new FacetsConfig();
    config.setMultiValued("a", false);
    try (Directory dir = newDirectory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir)) {
      Document doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("a", "foo"));
      doc.add(new SortedSetDocValuesFacetField("b", "bar"));
      writer.addDocument(config.build(doc));
      doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("a", "foo"));
      writer.addDocument(config.build(doc));
      if (random().nextBoolean()) {
        writer.commit();
      }

      doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("a", "baz"));
      writer.addDocument(config.build(doc));

      // NRT open
      try (IndexReader r = writer.getReader()) {
        IndexSearcher searcher = newSearcher(r);

        // Per-top-reader state:
        SortedSetDocValuesReaderState state =
            new DefaultSortedSetDocValuesReaderState(searcher.getIndexReader(), config);

        ExecutorService exec = randomExecutorServiceOrNull();
        try {
          Facets facets = getAllFacets(searcher, state, exec);

          assertEquals(
              "dim=a path=[] value=3 childCount=2\n  foo (2)\n  baz (1)\n",
              facets.getTopChildren(10, "a").toString());
          assertEquals(
              "dim=b path=[] value=1 childCount=1\n  bar (1)\n",
              facets.getTopChildren(10, "b").toString());

          // DrillDown:
          DrillDownQuery q = new DrillDownQuery(config);
          q.add("a", "foo");
          q.add("b", "bar");
          TopDocs hits = searcher.search(q, 1);
          assertEquals(1, hits.totalHits.value);
        } finally {
          if (exec != null) exec.shutdownNow();
        }
      }
    }
  }

  public void testHierarchicalBasicSingleValues() throws Exception {
    FacetsConfig config = new FacetsConfig();
    config.setHierarchical("c", true);
    try (Directory dir = newDirectory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir)) {
      Document doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("c", "buzz", "bar"));
      writer.addDocument(config.build(doc));
      doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("c", "buzz", "baz"));
      writer.addDocument(config.build(doc));
      if (random().nextBoolean()) {
        writer.commit();
      }

      doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("c", "baz"));
      writer.addDocument(config.build(doc));

      // NRT open
      try (IndexReader r = writer.getReader()) {
        IndexSearcher searcher = newSearcher(r);

        // Per-top-reader state:
        SortedSetDocValuesReaderState state =
            new DefaultSortedSetDocValuesReaderState(searcher.getIndexReader(), config);

        ExecutorService exec = randomExecutorServiceOrNull();
        try {
          Facets facets = getAllFacets(searcher, state, exec);

          assertEquals(
              "dim=c path=[buzz] value=2 childCount=2\n  bar (1)\n  baz (1)\n",
              facets.getTopChildren(10, "c", "buzz").toString());

          DrillDownQuery q = new DrillDownQuery(config);
          q.add("c", "buzz");
          TopDocs hits = searcher.search(q, 1);
          assertEquals(2, hits.totalHits.value);

          q = new DrillDownQuery(config);
          q.add("c", "buzz", "bar");
          hits = searcher.search(q, 1);
          assertEquals(1, hits.totalHits.value);
        } finally {
          if (exec != null) exec.shutdownNow();
        }
      }
    }
  }

  public void testDrillDownOptions() throws Exception {
    FacetsConfig config = new FacetsConfig();
    config.setDrillDownTermsIndexing("c", FacetsConfig.DrillDownTermsIndexing.NONE);
    config.setDrillDownTermsIndexing(
        "d", FacetsConfig.DrillDownTermsIndexing.DIMENSION_AND_FULL_PATH);
    config.setDrillDownTermsIndexing("e", FacetsConfig.DrillDownTermsIndexing.ALL_PATHS_NO_DIM);
    config.setDrillDownTermsIndexing("f", FacetsConfig.DrillDownTermsIndexing.FULL_PATH_ONLY);
    config.setDrillDownTermsIndexing("g", FacetsConfig.DrillDownTermsIndexing.ALL);
    try (Directory dir = newDirectory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir)) {

      Document doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("c", "foo"));
      doc.add(new SortedSetDocValuesFacetField("d", "foo"));
      doc.add(new SortedSetDocValuesFacetField("e", "foo"));
      doc.add(new SortedSetDocValuesFacetField("f", "foo"));
      doc.add(new SortedSetDocValuesFacetField("g", "foo"));
      writer.addDocument(config.build(doc));
      if (random().nextBoolean()) {
        writer.commit();
      }

      doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("a", "foo"));
      writer.addDocument(config.build(doc));

      // NRT open
      try (IndexReader r = writer.getReader()) {
        IndexSearcher searcher = newSearcher(r);
        // Drill down with different indexing configuration options
        DrillDownQuery q = new DrillDownQuery(config);
        q.add("c");
        TopDocs hits = searcher.search(q, 1);
        assertEquals(0, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("c", "foo");
        hits = searcher.search(q, 1);
        assertEquals(0, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("d");
        hits = searcher.search(q, 1);
        assertEquals(1, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("d", "foo");
        hits = searcher.search(q, 1);
        assertEquals(1, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("e");
        hits = searcher.search(q, 1);
        assertEquals(0, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("e", "foo");
        hits = searcher.search(q, 1);
        assertEquals(1, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("f");
        hits = searcher.search(q, 1);
        assertEquals(0, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("f", "foo");
        hits = searcher.search(q, 1);
        assertEquals(1, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("g");
        hits = searcher.search(q, 1);
        assertEquals(1, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("g", "foo");
        hits = searcher.search(q, 1);
        assertEquals(1, hits.totalHits.value);
      }
    }
  }

  public void testHierarchicalDrillDownOptions() throws Exception {
    FacetsConfig config = new FacetsConfig();
    config.setDrillDownTermsIndexing("c", FacetsConfig.DrillDownTermsIndexing.NONE);
    config.setDrillDownTermsIndexing(
        "d", FacetsConfig.DrillDownTermsIndexing.DIMENSION_AND_FULL_PATH);
    config.setDrillDownTermsIndexing("e", FacetsConfig.DrillDownTermsIndexing.ALL_PATHS_NO_DIM);
    config.setDrillDownTermsIndexing("f", FacetsConfig.DrillDownTermsIndexing.FULL_PATH_ONLY);
    config.setDrillDownTermsIndexing("g", FacetsConfig.DrillDownTermsIndexing.ALL);
    config.setHierarchical("c", true);
    config.setHierarchical("d", true);
    config.setHierarchical("e", true);
    config.setHierarchical("f", true);
    config.setHierarchical("g", true);
    try (Directory dir = newDirectory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir)) {

      Document doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("c", "biz", "baz"));
      doc.add(new SortedSetDocValuesFacetField("d", "biz", "baz"));
      doc.add(new SortedSetDocValuesFacetField("e", "biz", "baz"));
      doc.add(new SortedSetDocValuesFacetField("f", "biz", "baz"));
      doc.add(new SortedSetDocValuesFacetField("g", "biz", "baz"));
      writer.addDocument(config.build(doc));
      if (random().nextBoolean()) {
        writer.commit();
      }

      doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("a", "foo"));
      writer.addDocument(config.build(doc));

      // NRT open
      try (IndexReader r = writer.getReader()) {
        IndexSearcher searcher = newSearcher(r);
        // Drill down with different indexing configuration options
        DrillDownQuery q = new DrillDownQuery(config);
        q.add("c");
        TopDocs hits = searcher.search(q, 1);
        assertEquals(0, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("c", "biz");
        hits = searcher.search(q, 1);
        assertEquals(0, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("c", "biz", "baz");
        hits = searcher.search(q, 1);
        assertEquals(0, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("c", "foo");
        hits = searcher.search(q, 1);
        assertEquals(0, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("d");
        hits = searcher.search(q, 1);
        assertEquals(1, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("d", "foo");
        hits = searcher.search(q, 1);
        assertEquals(0, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("d", "biz");
        hits = searcher.search(q, 1);
        assertEquals(0, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("d", "biz", "baz");
        hits = searcher.search(q, 1);
        assertEquals(1, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("e");
        hits = searcher.search(q, 1);
        assertEquals(0, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("e", "foo");
        hits = searcher.search(q, 1);
        assertEquals(0, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("e", "biz");
        hits = searcher.search(q, 1);
        assertEquals(1, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("e", "biz", "baz");
        hits = searcher.search(q, 1);
        assertEquals(1, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("f");
        hits = searcher.search(q, 1);
        assertEquals(0, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("f", "foo");
        hits = searcher.search(q, 1);
        assertEquals(0, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("f", "biz");
        hits = searcher.search(q, 1);
        assertEquals(0, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("f", "biz", "baz");
        hits = searcher.search(q, 1);
        assertEquals(1, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("g");
        hits = searcher.search(q, 1);
        assertEquals(1, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("g", "foo");
        hits = searcher.search(q, 1);
        assertEquals(0, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("g", "biz");
        hits = searcher.search(q, 1);
        assertEquals(1, hits.totalHits.value);

        q = new DrillDownQuery(config);
        q.add("g", "biz", "baz");
        hits = searcher.search(q, 1);
        assertEquals(1, hits.totalHits.value);
      }
    }
  }

  // LUCENE-5090
  @SuppressWarnings("unused")
  public void testStaleState() throws Exception {
    try (Directory dir = newDirectory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir)) {

      FacetsConfig config = new FacetsConfig();

      Document doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("a", "foo"));
      writer.addDocument(config.build(doc));

      try (IndexReader r = writer.getReader()) {
        SortedSetDocValuesReaderState state = new DefaultSortedSetDocValuesReaderState(r, config);

        doc = new Document();
        doc.add(new SortedSetDocValuesFacetField("a", "bar"));
        writer.addDocument(config.build(doc));

        doc = new Document();
        doc.add(new SortedSetDocValuesFacetField("a", "baz"));
        writer.addDocument(config.build(doc));

        try (IndexReader r2 = writer.getReader()) {
          IndexSearcher searcher = newSearcher(r2);

          FacetsCollector c =
              searcher.search(new MatchAllDocsQuery(), new FacetsCollectorManager());

          expectThrows(
              IllegalStateException.class,
              () -> {
                new SortedSetDocValuesFacetCounts(state, c);
              });
        }
      }
    }
  }

  // LUCENE-5333
  public void testSparseFacets() throws Exception {
    try (Directory dir = newDirectory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir)) {

      FacetsConfig config = new FacetsConfig();

      Document doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("a", "foo1"));
      writer.addDocument(config.build(doc));

      if (random().nextBoolean()) {
        writer.commit();
      }

      doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("a", "foo2"));
      doc.add(new SortedSetDocValuesFacetField("b", "bar1"));
      writer.addDocument(config.build(doc));

      if (random().nextBoolean()) {
        writer.commit();
      }

      doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("a", "foo3"));
      doc.add(new SortedSetDocValuesFacetField("b", "bar2"));
      doc.add(new SortedSetDocValuesFacetField("c", "baz1"));
      writer.addDocument(config.build(doc));

      // NRT open
      try (IndexReader r = writer.getReader()) {
        IndexSearcher searcher = newSearcher(r);

        // Per-top-reader state:
        SortedSetDocValuesReaderState state =
            new DefaultSortedSetDocValuesReaderState(searcher.getIndexReader(), config);

        ExecutorService exec = randomExecutorServiceOrNull();
        try {
          Facets facets = getAllFacets(searcher, state, exec);

          // Ask for top 10 labels for any dims that have counts:
          List<FacetResult> results = facets.getAllDims(10);

          assertEquals(3, results.size());
          assertEquals(
              "dim=a path=[] value=3 childCount=3\n  foo1 (1)\n  foo2 (1)\n  foo3 (1)\n",
              results.get(0).toString());
          assertEquals(
              "dim=b path=[] value=2 childCount=2\n  bar1 (1)\n  bar2 (1)\n",
              results.get(1).toString());
          assertEquals(
              "dim=c path=[] value=1 childCount=1\n  baz1 (1)\n", results.get(2).toString());

          Collection<Accountable> resources = state.getChildResources();
          assertTrue(state.toString().contains(FacetsConfig.DEFAULT_INDEX_FIELD_NAME));
          if (searcher.getIndexReader().leaves().size() > 1) {
            assertTrue(state.ramBytesUsed() > 0);
            assertFalse(resources.isEmpty());
            assertTrue(resources.toString().contains(FacetsConfig.DEFAULT_INDEX_FIELD_NAME));
          } else {
            assertEquals(0, state.ramBytesUsed());
            assertTrue(resources.isEmpty());
          }
        } finally {
          if (exec != null) exec.shutdownNow();
        }
      }
    }
  }

  public void testHierarchicalSparseFacets() throws Exception {
    try (Directory dir = newDirectory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir)) {

      FacetsConfig config = new FacetsConfig();
      config.setHierarchical("d", true);
      config.setHierarchical("e", true);

      Document doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("d", "foo", "bar"));
      writer.addDocument(config.build(doc));

      if (random().nextBoolean()) {
        writer.commit();
      }

      doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("d", "foo", "baz"));
      writer.addDocument(config.build(doc));

      if (random().nextBoolean()) {
        writer.commit();
      }

      doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("e", "biz", "baz"));
      writer.addDocument(config.build(doc));

      // NRT open
      try (IndexReader r = writer.getReader()) {
        IndexSearcher searcher = newSearcher(r);

        // Per-top-reader state:
        SortedSetDocValuesReaderState state =
            new DefaultSortedSetDocValuesReaderState(searcher.getIndexReader(), config);

        ExecutorService exec = randomExecutorServiceOrNull();
        try {
          Facets facets = getAllFacets(searcher, state, exec);

          // Ask for top 10 labels for any dims that have counts:
          List<FacetResult> results = facets.getAllDims(10);

          assertEquals(2, results.size());
          assertEquals(
              "dim=d path=[] value=2 childCount=1\n  foo (2)\n", results.get(0).toString());
          assertEquals(
              "dim=e path=[] value=1 childCount=1\n  biz (1)\n", results.get(1).toString());

          Collection<Accountable> resources = state.getChildResources();
          assertTrue(state.toString().contains(FacetsConfig.DEFAULT_INDEX_FIELD_NAME));
          if (searcher.getIndexReader().leaves().size() > 1) {
            assertTrue(state.ramBytesUsed() > 0);
            assertFalse(resources.isEmpty());
            assertTrue(resources.toString().contains(FacetsConfig.DEFAULT_INDEX_FIELD_NAME));
          } else {
            assertEquals(0, state.ramBytesUsed());
            assertTrue(resources.isEmpty());
          }
        } finally {
          if (exec != null) exec.shutdownNow();
        }
      }
    }
  }

  public void testSomeSegmentsMissing() throws Exception {
    try (Directory dir = newDirectory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir)) {

      FacetsConfig config = new FacetsConfig();

      Document doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("a", "foo1"));
      writer.addDocument(config.build(doc));
      writer.commit();

      doc = new Document();
      writer.addDocument(config.build(doc));
      writer.commit();

      doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("a", "foo2"));
      writer.addDocument(config.build(doc));
      writer.commit();

      // NRT open
      try (IndexReader r = writer.getReader()) {
        IndexSearcher searcher = newSearcher(r);

        // Per-top-reader state:
        SortedSetDocValuesReaderState state =
            new DefaultSortedSetDocValuesReaderState(searcher.getIndexReader(), config);

        ExecutorService exec = randomExecutorServiceOrNull();
        try {
          Facets facets = getAllFacets(searcher, state, exec);

          // Ask for top 10 labels for any dims that have counts:
          assertEquals(
              "dim=a path=[] value=2 childCount=2\n  foo1 (1)\n  foo2 (1)\n",
              facets.getTopChildren(10, "a").toString());
        } finally {
          if (exec != null) exec.shutdownNow();
        }
      }
    }
  }

  public void testHierarchicalSomeSegmentsMissing() throws Exception {
    try (Directory dir = newDirectory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir)) {

      FacetsConfig config = new FacetsConfig();
      config.setHierarchical("b", true);

      Document doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("a", "foo1"));
      doc.add(new SortedSetDocValuesFacetField("b", "foo", "bar"));
      writer.addDocument(config.build(doc));
      writer.commit();

      doc = new Document();
      writer.addDocument(config.build(doc));
      writer.commit();

      doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("a", "foo2"));
      doc.add(new SortedSetDocValuesFacetField("b", "foo", "buzz"));
      writer.addDocument(config.build(doc));
      writer.commit();

      // NRT open
      try (IndexReader r = writer.getReader()) {
        IndexSearcher searcher = newSearcher(r);

        // Per-top-reader state:
        SortedSetDocValuesReaderState state =
            new DefaultSortedSetDocValuesReaderState(searcher.getIndexReader(), config);

        ExecutorService exec = randomExecutorServiceOrNull();
        try {
          Facets facets = getAllFacets(searcher, state, exec);

          // Ask for top 10 labels for any dims that have counts:
          assertEquals(
              "dim=a path=[] value=2 childCount=2\n  foo1 (1)\n  foo2 (1)\n",
              facets.getTopChildren(10, "a").toString());
          assertEquals(
              "dim=b path=[] value=2 childCount=1\n  foo (2)\n",
              facets.getTopChildren(10, "b").toString());
          assertEquals(
              "dim=b path=[foo] value=2 childCount=2\n  bar (1)\n  buzz (1)\n",
              facets.getTopChildren(10, "b", "foo").toString());
        } finally {
          if (exec != null) exec.shutdownNow();
        }
      }
    }
  }

  public void testRandom() throws Exception {
    int fullIterations = LuceneTestCase.TEST_NIGHTLY ? 20 : 3;
    for (int fullIter = 0; fullIter < fullIterations; fullIter++) {
      String[] tokens = getRandomTokens(10);

      try (Directory indexDir = newDirectory();
          RandomIndexWriter w = new RandomIndexWriter(random(), indexDir)) {
        FacetsConfig config = new FacetsConfig();
        int numDocs = atLeast(1000);
        // Most of the time allow up to 7 dims per doc, but occasionally limit all docs to a single
        // dim:
        int numDims;
        if (random().nextInt(10) < 8) {
          numDims = TestUtil.nextInt(random(), 1, 7);
        } else {
          numDims = 1;
        }
        List<TestDoc> testDocs = getRandomDocs(tokens, numDocs, numDims);
        for (TestDoc testDoc : testDocs) {
          Document doc = new Document();
          doc.add(newStringField("content", testDoc.content, Field.Store.NO));
          for (int j = 0; j < numDims; j++) {
            if (testDoc.dims[j] != null) {
              doc.add(new SortedSetDocValuesFacetField("dim" + j, testDoc.dims[j]));
            }
          }
          w.addDocument(config.build(doc));
        }

        // NRT open
        try (IndexReader r = w.getReader()) {
          IndexSearcher searcher = newSearcher(r);

          // Per-top-reader state:
          SortedSetDocValuesReaderState state =
              new DefaultSortedSetDocValuesReaderState(searcher.getIndexReader(), config);
          ExecutorService exec = randomExecutorServiceOrNull();
          try {
            int iters = atLeast(100);
            for (int iter = 0; iter < iters; iter++) {
              String searchToken = tokens[random().nextInt(tokens.length)];
              if (VERBOSE) {
                System.out.println("\nTEST: iter content=" + searchToken);
              }
              FacetsCollector fc = new FacetsCollector();
              FacetsCollector.search(
                  searcher, new TermQuery(new Term("content", searchToken)), 10, fc);
              Facets facets;
              if (exec != null) {
                facets = new ConcurrentSortedSetDocValuesFacetCounts(state, fc, exec);
              } else {
                facets = new SortedSetDocValuesFacetCounts(state, fc);
              }

              // Slow, yet hopefully bug-free, faceting:
              @SuppressWarnings({"rawtypes", "unchecked"})
              Map<String, Integer>[] expectedCounts = new HashMap[numDims];
              for (int i = 0; i < numDims; i++) {
                expectedCounts[i] = new HashMap<>();
              }

              for (TestDoc doc : testDocs) {
                if (doc.content.equals(searchToken)) {
                  for (int j = 0; j < numDims; j++) {
                    if (doc.dims[j] != null) {
                      Integer v = expectedCounts[j].get(doc.dims[j]);
                      if (v == null) {
                        expectedCounts[j].put(doc.dims[j], 1);
                      } else {
                        expectedCounts[j].put(doc.dims[j], v.intValue() + 1);
                      }
                    }
                  }
                }
              }

              List<FacetResult> expected = new ArrayList<>();
              for (int i = 0; i < numDims; i++) {
                List<LabelAndValue> labelValues = new ArrayList<>();
                int totCount = 0;
                for (Map.Entry<String, Integer> ent : expectedCounts[i].entrySet()) {
                  labelValues.add(new LabelAndValue(ent.getKey(), ent.getValue()));
                  totCount += ent.getValue();
                }
                sortLabelValues(labelValues);
                if (totCount > 0) {
                  expected.add(
                      new FacetResult(
                          "dim" + i,
                          new String[0],
                          totCount,
                          labelValues.toArray(new LabelAndValue[labelValues.size()]),
                          labelValues.size()));
                }
              }

              // Sort by highest value, tie break by value:
              sortFacetResults(expected);

              List<FacetResult> actual = facets.getAllDims(10);

              // Messy: fixup ties
              // sortTies(actual);

              assertEquals(expected, actual);
            }
          } finally {
            if (exec != null) exec.shutdownNow();
          }
        }
      }
    }
  }

  public void testRandomHierarchicalFlatMix() throws Exception {
    int fullIterations = LuceneTestCase.TEST_NIGHTLY ? 20 : 3;
    for (int fullIter = 0; fullIter < fullIterations; fullIter++) {
      String[] tokens = getRandomTokens(10);

      try (Directory indexDir = newDirectory();
          RandomIndexWriter w = new RandomIndexWriter(random(), indexDir)) {
        FacetsConfig config = new FacetsConfig();
        int numDocs = atLeast(1000);
        // Most of the time allow up to 7 dims per doc, but occasionally limit all docs to a single
        // dim:
        int numDims;
        if (random().nextInt(10) < 8) {
          numDims = TestUtil.nextInt(random(), 1, 7);
        } else {
          numDims = 1;
        }
        boolean[] hierarchicalDims = new boolean[numDims];
        for (int i = 0; i < numDims; i++) {
          boolean isHierarchicalDim = random().nextBoolean();
          config.setHierarchical("dim" + i, isHierarchicalDim);
          hierarchicalDims[i] = isHierarchicalDim;
        }
        List<TestDoc> testDocs = getRandomDocs(tokens, numDocs, numDims);
        List<Set<SortedSetDocValuesFacetField>> testDocFacets = new ArrayList<>();
        for (TestDoc testDoc : testDocs) {
          Document doc = new Document();
          Set<SortedSetDocValuesFacetField> docFacets = new HashSet<>();
          doc.add(newStringField("content", testDoc.content, Field.Store.NO));
          for (int i = 0; i < numDims; i++) {
            if (hierarchicalDims[i]) {
              int pathLength;
              if (numDims == 1) {
                pathLength = 1;
              } else {
                pathLength = random().nextInt(numDims - 1) + 1;
              }
              List<String> path = new ArrayList<>();
              for (int j = 0; j < pathLength; j++) {
                if (testDoc.dims[j] != null) {
                  path.add(testDoc.dims[j]);
                }
              }
              doc.add(new SortedSetDocValuesFacetField("dim" + i, path.toArray(String[]::new)));
              for (int j = 0; j < path.size(); j++) {
                docFacets.add(
                    new SortedSetDocValuesFacetField(
                        "dim" + i, path.subList(0, j + 1).toArray(String[]::new)));
              }
            } else if (testDoc.dims[i] != null) {
              doc.add(new SortedSetDocValuesFacetField("dim" + i, testDoc.dims[i]));
              docFacets.add(new SortedSetDocValuesFacetField("dim" + i, testDoc.dims[i]));
            }
          }
          testDocFacets.add(docFacets);
          w.addDocument(config.build(doc));
        }

        // NRT open
        try (IndexReader r = w.getReader()) {
          IndexSearcher searcher = newSearcher(r);

          // Per-top-reader state:
          SortedSetDocValuesReaderState state =
              new DefaultSortedSetDocValuesReaderState(searcher.getIndexReader(), config);
          ExecutorService exec = randomExecutorServiceOrNull();
          try {
            int iters = atLeast(100);
            for (int iter = 0; iter < iters; iter++) {
              String searchToken = tokens[random().nextInt(tokens.length)];
              if (VERBOSE) {
                System.out.println("\nTEST: iter content=" + searchToken);
              }
              FacetsCollector fc = new FacetsCollector();
              FacetsCollector.search(
                  searcher, new TermQuery(new Term("content", searchToken)), 10, fc);
              Facets facets;
              if (exec != null) {
                facets = new ConcurrentSortedSetDocValuesFacetCounts(state, fc, exec);
              } else {
                facets = new SortedSetDocValuesFacetCounts(state, fc);
              }
              // Slow, yet hopefully bug-free, faceting:
              Map<String, FacetResult> expectedResults = new HashMap<>();

              for (int i = 0; i < testDocs.size(); i++) {
                TestDoc doc = testDocs.get(i);
                if (doc.content.equals(searchToken)) {
                  // goes through all facets paths in the doc
                  for (SortedSetDocValuesFacetField facetField : testDocFacets.get(i)) {
                    String[] path = facetField.path;
                    String parentDimPathString;
                    if (path.length == 1) {
                      parentDimPathString = facetField.dim;
                    } else {
                      parentDimPathString =
                          facetField.dim
                              + FacetsConfig.DELIM_CHAR
                              + FacetsConfig.pathToString(path, path.length - 1);
                    }
                    FacetResult result = expectedResults.get(parentDimPathString);
                    if (result == null) {
                      String[] resultPath = new String[path.length - 1];
                      System.arraycopy(path, 0, resultPath, 0, resultPath.length);
                      result =
                          new FacetResult(facetField.dim, resultPath, 0, new LabelAndValue[0], 0);
                    }
                    String child = path[path.length - 1];
                    LabelAndValue[] labelAndValues = result.labelValues;
                    boolean containsChild = false;
                    for (int k = 0; k < labelAndValues.length; k++) {
                      if (labelAndValues[k].label.equals(child)) {
                        containsChild = true;
                        labelAndValues[k] =
                            new LabelAndValue(
                                labelAndValues[k].label, labelAndValues[k].value.intValue() + 1);
                        break;
                      }
                    }
                    LabelAndValue[] newLabelAndValues;
                    int childCount = result.childCount;
                    if (containsChild == false) {
                      newLabelAndValues = new LabelAndValue[labelAndValues.length + 1];
                      System.arraycopy(
                          labelAndValues, 0, newLabelAndValues, 0, labelAndValues.length);
                      newLabelAndValues[newLabelAndValues.length - 1] = new LabelAndValue(child, 1);
                      childCount++;
                    } else {
                      newLabelAndValues = labelAndValues;
                    }
                    newLabelAndValues =
                        Arrays.stream(newLabelAndValues)
                            .sorted(
                                (o1, o2) -> {
                                  if (o1.value.equals(o2.value)) {
                                    return new BytesRef(o1.label).compareTo(new BytesRef(o2.label));
                                  } else {
                                    return o2.value.intValue() - o1.value.intValue();
                                  }
                                })
                            .collect(Collectors.toList())
                            .toArray(LabelAndValue[]::new);
                    FacetResult newResult =
                        new FacetResult(result.dim, result.path, 0, newLabelAndValues, childCount);
                    expectedResults.put(parentDimPathString, newResult);
                  }
                }
              }

              // second pass to update values
              for (int i = 0; i < testDocs.size(); i++) {
                TestDoc doc = testDocs.get(i);
                if (doc.content.equals(searchToken)) {
                  Set<String> dimsCounted = new HashSet<>();
                  for (SortedSetDocValuesFacetField facetField : testDocFacets.get(i)) {
                    String dimPathString =
                        FacetsConfig.pathToString(facetField.dim, facetField.path);
                    FacetResult result = expectedResults.get(dimPathString);
                    FacetResult dimResult = expectedResults.get(facetField.dim);
                    if (result != null) {
                      expectedResults.put(
                          dimPathString,
                          new FacetResult(
                              result.dim,
                              result.path,
                              result.value.intValue() + 1,
                              result.labelValues,
                              result.childCount));
                    }
                    if (dimResult != null && dimsCounted.add(facetField.dim)) {
                      expectedResults.put(
                          facetField.dim,
                          new FacetResult(
                              dimResult.dim,
                              dimResult.path,
                              dimResult.value.intValue() + 1,
                              dimResult.labelValues,
                              dimResult.childCount));
                    }
                  }
                }
              }

              List<FacetResult> expected = new ArrayList<>(expectedResults.values());

              List<FacetResult> expectedAllDims = new ArrayList<>();
              for (FacetResult result : expected) {
                if (result.path.length == 0) {
                  expectedAllDims.add(result);
                  if (expectedAllDims.size() >= 10) {
                    break;
                  }
                }
              }
              sortFacetResults(expectedAllDims);

              List<FacetResult> actualAllDims = facets.getAllDims(10);

              assertEquals(expectedAllDims, actualAllDims);

              // Dfs through top children
              for (FacetResult dimResult : actualAllDims) {
                if (config.getDimConfig(dimResult.dim).hierarchical) {
                  Stack<String[]> stack = new Stack<>();
                  for (LabelAndValue labelAndValue : dimResult.labelValues) {
                    String[] path = new String[1];
                    path[0] = labelAndValue.label;
                    stack.add(path);
                  }
                  while (stack.empty() == false) {
                    String[] currPath = stack.pop();
                    FacetResult expectedResult =
                        getFacetResultForPath(expected, dimResult.dim, currPath);
                    FacetResult actualResult = facets.getTopChildren(10, dimResult.dim, currPath);
                    try {
                      assertEquals(expectedResult, actualResult);
                    } catch (AssertionError e) {
                      System.out.println(iter);
                      System.out.println(config.getDimConfig(dimResult.dim).hierarchical);
                      throw e;
                    }
                    if (actualResult != null) {
                      for (LabelAndValue labelAndValue : actualResult.labelValues) {
                        String[] path = new String[currPath.length + 1];
                        System.arraycopy(currPath, 0, path, 0, currPath.length);
                        path[path.length - 1] = labelAndValue.label;
                        stack.add(path);
                      }
                    }
                  }
                }
              }
            }
          } finally {
            if (exec != null) exec.shutdownNow();
          }
        }
      }
    }
  }

  private static FacetResult getFacetResultForPath(
      List<FacetResult> allPaths, String dim, String[] path) {
    for (FacetResult result : allPaths) {
      if (path.length == 0) {
        if (result.path.length == 0 && result.dim.equals(dim)) {
          return result;
        }
      } else {
        boolean isEqualPath = true;
        if (path.length != result.path.length) {
          isEqualPath = false;
        } else {
          for (int i = 0; i < path.length; i++) {
            if (path[i].equals(result.path[i]) == false) {
              isEqualPath = false;
              break;
            }
          }
        }
        if (isEqualPath && result.dim.equals(dim)) {
          return result;
        }
      }
    }
    return null;
  }

  public void testNonExistentDimension() throws Exception {
    try (Directory dir = newDirectory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir)) {
      FacetsConfig config = new FacetsConfig();

      Document doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("foo", "bar"));
      writer.addDocument(config.build(doc));
      writer.commit();

      try (IndexReader r = writer.getReader()) {
        IndexSearcher searcher = newSearcher(r);

        SortedSetDocValuesReaderState state =
            new DefaultSortedSetDocValuesReaderState(searcher.getIndexReader(), config);

        ExecutorService exec = randomExecutorServiceOrNull();
        try {
          Facets facets = getAllFacets(searcher, state, exec);
          FacetResult result = facets.getTopChildren(5, "non-existent dimension");

          // make sure the result is null (and no exception was thrown)
          assertNull(result);
        } finally {
          if (exec != null) exec.shutdownNow();
        }
      }
    }
  }

  public void testHierarchicalNonExistentDimension() throws Exception {
    try (Directory dir = newDirectory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir)) {
      FacetsConfig config = new FacetsConfig();
      config.setHierarchical("fizz", true);

      Document doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("foo", "bar"));
      doc.add(new SortedSetDocValuesFacetField("fizz", "buzz", "baz"));
      writer.addDocument(config.build(doc));
      writer.commit();

      try (IndexReader r = writer.getReader()) {
        IndexSearcher searcher = newSearcher(r);

        SortedSetDocValuesReaderState state =
            new DefaultSortedSetDocValuesReaderState(searcher.getIndexReader(), config);

        ExecutorService exec = randomExecutorServiceOrNull();
        try {
          Facets facets = getAllFacets(searcher, state, exec);
          FacetResult result = facets.getTopChildren(5, "non-existent dimension");

          // make sure the result is null (and no exception was thrown)
          assertNull(result);

          expectThrows(
              IllegalArgumentException.class,
              () -> {
                facets.getTopChildren(5, "non-existent dimension", "with a path");
              });
        } finally {
          if (exec != null) exec.shutdownNow();
        }
      }
    }
  }

  public void testHierarchicalNonExistentPath() throws Exception {
    try (Directory dir = newDirectory();
        RandomIndexWriter writer = new RandomIndexWriter(random(), dir)) {
      FacetsConfig config = new FacetsConfig();
      config.setHierarchical("fizz", true);

      Document doc = new Document();
      doc.add(new SortedSetDocValuesFacetField("fizz", "buzz", "baz"));
      writer.addDocument(config.build(doc));
      writer.commit();

      try (IndexReader r = writer.getReader()) {
        IndexSearcher searcher = newSearcher(r);

        SortedSetDocValuesReaderState state =
            new DefaultSortedSetDocValuesReaderState(searcher.getIndexReader(), config);

        ExecutorService exec = randomExecutorServiceOrNull();
        try {
          Facets facets = getAllFacets(searcher, state, exec);
          FacetResult result = facets.getTopChildren(5, "fizz", "fake", "path");

          // make sure the result is null (and no exception was thrown)
          assertNull(result);
        } finally {
          if (exec != null) exec.shutdownNow();
        }
      }
    }
  }

  private static Facets getAllFacets(
      IndexSearcher searcher, SortedSetDocValuesReaderState state, ExecutorService exec)
      throws IOException, InterruptedException {
    if (random().nextBoolean()) {
      FacetsCollector c = searcher.search(new MatchAllDocsQuery(), new FacetsCollectorManager());
      if (exec != null) {
        return new ConcurrentSortedSetDocValuesFacetCounts(state, c, exec);
      } else {
        return new SortedSetDocValuesFacetCounts(state, c);
      }
    } else if (exec != null) {
      return new ConcurrentSortedSetDocValuesFacetCounts(state, exec);
    } else {
      return new SortedSetDocValuesFacetCounts(state);
    }
  }

  private ExecutorService randomExecutorServiceOrNull() {
    if (random().nextBoolean()) {
      return null;
    } else {
      return new ThreadPoolExecutor(
          1,
          TestUtil.nextInt(random(), 2, 6),
          Long.MAX_VALUE,
          TimeUnit.MILLISECONDS,
          new LinkedBlockingQueue<Runnable>(),
          new NamedThreadFactory("TestIndexSearcher"));
    }
  }
}
