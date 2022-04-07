package com.udacity.webcrawler;

import com.udacity.webcrawler.json.CrawlResult;
import com.udacity.webcrawler.parser.PageParser;
import com.udacity.webcrawler.parser.PageParserFactory;

import javax.inject.Inject;
import javax.inject.Provider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A concrete implementation of {@link WebCrawler} that runs multiple threads on a
 * {@link ForkJoinPool} to fetch and process multiple web pages in parallel.
 */
final class ParallelWebCrawler implements WebCrawler {
  private final Clock clock;
  private final Duration timeout;
  private final int popularWordCount;
  private final ForkJoinPool pool;
  private final PageParserFactory parserFactory;
  private final List<Pattern> ignoredUrls;
  private final int maxDepth;

  //public static Lock lock = new ReentrantLock();

  @Inject
  ParallelWebCrawler(
      Clock clock,
      @Timeout Duration timeout,
      @PopularWordCount int popularWordCount,
      @TargetParallelism int threadCount,
      @IgnoredUrls List<Pattern> ignoredUrls,
      @MaxDepth int maxDepth,
      PageParserFactory parserFactory) {
    this.clock = clock;
    this.timeout = timeout;
    this.popularWordCount = popularWordCount;
    this.pool = new ForkJoinPool(Math.min(threadCount, getMaxParallelism()));
    this.ignoredUrls = ignoredUrls;
    this.maxDepth = maxDepth;
    this.parserFactory = parserFactory;
  }

  @Override
  public CrawlResult crawl(List<String> startingUrls) {
    Instant deadLine = clock.instant().plus(timeout);
    ConcurrentHashMap <String, Integer> counts = new ConcurrentHashMap<>();
    ConcurrentSkipListSet <String> visitedUrls = new ConcurrentSkipListSet<>();

    for (String url : startingUrls) {
      pool.invoke(new parallelCrawlerInternal(url, deadLine, maxDepth, counts, visitedUrls, clock, parserFactory, ignoredUrls));
    }
    //return new CrawlResult.Builder().build();
    if (counts.isEmpty()) {
      return new CrawlResult.Builder()
              .setWordCounts(counts)
              .setUrlsVisited(visitedUrls.size())
              .build();
    }

    return new CrawlResult.Builder()
            .setWordCounts(WordCounts.sort(counts, popularWordCount))
            .setUrlsVisited(visitedUrls.size())
            .build();
  }

  public static class parallelCrawlerInternal extends RecursiveTask<Boolean> {
    private final String url;
    private final Instant deadLine;
    private final int maxDepth;
    private final ConcurrentMap<String, Integer> counts;
    private final ConcurrentSkipListSet<String> visitedUrls;
    private final Clock clock;
    private final PageParserFactory parserFactory;
    private final List<Pattern> ignoredUrls;

    public parallelCrawlerInternal(String url, Instant deadLine, int maxDepth,
                                   ConcurrentMap<String, Integer> counts,
                                   ConcurrentSkipListSet<String> visitedUrls, Clock clock,
                                   PageParserFactory parserFactory, List<Pattern> ignoredUrls) {

      this.url = url;
      this.deadLine = deadLine;
      this.maxDepth = maxDepth;
      this.counts = counts;
      this.visitedUrls = visitedUrls;
      this.clock = clock;
      this.parserFactory = parserFactory;
      this.ignoredUrls = ignoredUrls;
    }

    @Override
    protected Boolean compute() {
      if (maxDepth == 0 || clock.instant().isAfter(deadLine)) {
        return false;
      }

      for (Pattern pattern : ignoredUrls) {
        if (pattern.matcher(url).matches()) {
          return false;
        }
      }
      ReentrantLock lock = new ReentrantLock();
        try {
          lock.lock();
          if (visitedUrls.contains(url)) {
            return false;
          }
          visitedUrls.add(url);
        }catch (Exception ex) {
          ex.printStackTrace();
        }finally {
          lock.unlock();
        }

        PageParser.Result result = parserFactory.get(url).parse();
        for (ConcurrentMap.Entry<String, Integer> e : result.getWordCounts().entrySet()) {
          if (counts.containsKey(e.getKey())) {
            counts.put(e.getKey(), e.getValue() + counts.get(e.getKey()));
          }else {
            counts.put(e.getKey(), e.getValue());
          }
        }

        List<parallelCrawlerInternal> subtasks = new ArrayList<>();
        for (String link : result.getLinks()) {
          subtasks.add(new parallelCrawlerInternal(link, deadLine, maxDepth - 1, counts, visitedUrls, clock,
                  parserFactory, ignoredUrls));
        }
        invokeAll(subtasks);
        return true;
      }
    }

    @Override
    public int getMaxParallelism() {
      return Runtime.getRuntime().availableProcessors();
    }
  }





