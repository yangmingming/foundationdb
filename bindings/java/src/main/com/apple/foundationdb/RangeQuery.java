/*
 * RangeQuery.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2013-2018 Apple Inc. and the FoundationDB project authors
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apple.foundationdb;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;

import com.apple.foundationdb.async.*;
import com.apple.foundationdb.async.AsyncIterable;
import com.apple.foundationdb.async.Function;
import com.apple.foundationdb.async.Future;

/**
 * Represents a query against FoundationDB for a range of keys. The
 *  result of this query can be iterated over in a blocking fashion with a call to
 *  {@link #iterator()} (as specified by {@link Iterable}).
 *  If the calling program uses an asynchronous paradigm, a non-blocking
 *  {@link AsyncIterator} is returned from {@link #asyncIterator()}. Both of these
 *  constructions will not begin to query the database until the first call to
 *  {@code hasNext()}. As the query uses its {@link Transaction} of origin to fetch
 *  all the data, the use of this query object must not span more than a few seconds.
 *
 * <br><br><b>NOTE:</b> although resulting {@code Iterator}s do support the {@code remove()}
 *   operation, the remove is not durable until {@code commit()} on the {@code Transaction}
 *   that yielded this query returns <code>true</code>.
 */
class RangeQuery implements AsyncIterable<KeyValue>, Iterable<KeyValue> {
	private final FDBTransaction tr;
	private final KeySelector begin;
	private final KeySelector end;
	private final boolean snapshot;
	private final int rowLimit;
	private final boolean reverse;
	private final StreamingMode streamingMode;
	private final FutureResults firstChunk;

	private RangeQuery(FDBTransaction transaction, boolean isSnapshot,
			KeySelector begin, KeySelector end, int rowLimit,
			boolean reverse, StreamingMode streamingMode,
			FutureResults firstChunk) {
		this.tr = transaction;
		this.begin = begin;
		this.end = end;
		this.snapshot = isSnapshot;
		this.rowLimit = rowLimit;
		this.reverse = reverse;
		this.streamingMode = streamingMode;
		this.firstChunk = firstChunk;
	}

	static RangeQuery start(FDBTransaction transaction, boolean isSnapshot,
							KeySelector begin, KeySelector end, int rowLimit,
							boolean reverse, StreamingMode streamingMode) {
		// start the first fetch...
		FutureResults firstChunk = transaction.getRange_internal(begin, end,
				rowLimit, 0, streamingMode.code(), 1, isSnapshot, reverse);

		return new RangeQuery(transaction, isSnapshot, begin, end, rowLimit, reverse, streamingMode, firstChunk);
	}

	/**
	 * Returns all the results from the range requested as a {@code List}. If there were no
	 *  limits on the original query and there is a large amount of data in the database
	 *  this call could use a very large amount of memory.
	 *
	 * @return a {@code Future} that will be set to the contents of the database
	 *  constrained by the query parameters.
	 */
	@Override
	public Future<List<KeyValue>> asList() {
		StreamingMode mode = this.streamingMode;
		if(mode == StreamingMode.ITERATOR)
			mode = (this.rowLimit == 0) ? StreamingMode.WANT_ALL : StreamingMode.EXACT;

		// if the streaming mode is EXACT, try and grab things as one chunk
		if(mode == StreamingMode.EXACT) {
			Future<RangeResult> range = tr.getRange_internal(
					this.begin, this.end, this.rowLimit, 0, StreamingMode.EXACT.code(),
					1, this.snapshot, this.reverse);
			return range.map(new Function<RangeResult, List<KeyValue>>() {
				@Override
				public List<KeyValue> apply(RangeResult o) {
					return o.values;
				}
			});
		}

		// If the streaming mode is not EXACT, simply collect the results of an iteration into a list
		return AsyncUtil.collect(
				new RangeQuery(tr, snapshot, begin, end, rowLimit, reverse, mode, firstChunk));
	}

	/**
	 *  Returns an {@code Iterator} over the results of this query against FoundationDB.
	 *
	 *  @return an {@code Iterator} over type {@code KeyValue}.
	 */
	@Override
	public AsyncRangeIterator iterator() {
		return new AsyncRangeIterator(this.rowLimit, this.reverse, this.streamingMode);
	}

	private class AsyncRangeIterator implements AsyncIterator<KeyValue> {
		// immutable aspects of this iterator
		private final boolean rowsLimited;
		private final boolean reverse;
		private final StreamingMode streamingMode;

		// There is the chance for parallelism in the two "chunks" for fetched data
		private RangeResult chunk = null;
		private RangeResult nextChunk = null;
		private boolean fetchOutstanding = true;
		private byte[] prevKey = null;
		private int index = 0;
		// The first request is made in the constructor for the parent Iterable, so start at 1
		private int iteration = 1;
		private KeySelector begin;
		private KeySelector end;

		private int rowsRemaining;

		private Future<Boolean> nextFuture;
		private boolean isCancelled = false;

		private AsyncRangeIterator(int rowLimit, boolean reverse, StreamingMode streamingMode) {
			this.begin = RangeQuery.this.begin;
			this.end = RangeQuery.this.end;
			this.rowsLimited = rowLimit != 0;
			this.rowsRemaining = rowLimit;
			this.reverse = reverse;
			this.streamingMode = streamingMode;

			// Register for completion, etc. on the first chunk. Some of the fields in
			//  this class were initialized with the knowledge that this fetch is active
			//  at creation time. This set normally happens in startNextFetch, but
			//  the first fetch has already been configured and started.
			SettableFuture<Boolean> promise = new SettableFuture<Boolean>(tr.getExecutor());
			nextFuture = promise;

			// FIXME: should we propagate cancellation into the first chuck fetch?
			//  This would invalidate the whole iterable, not just the iterator
			//promise.onCancelledCancel(firstChunk);

			firstChunk.onReady(new FetchComplete(firstChunk, promise));
		}

		private synchronized boolean mainChunkIsTheLast() {
			return !chunk.more || (rowsLimited && rowsRemaining < 1);
		}

		class FetchComplete implements Runnable {
			final FutureResults fetchingChunk;
			final Settable<Boolean> promise;

			public FetchComplete(FutureResults fetch, Settable<Boolean> promise) {
				this.fetchingChunk = fetch;
				this.promise = promise;
			}

			@Override
			public void run() {
				final RangeResultSummary summary;
				boolean toSet = false;
				try {
					summary = fetchingChunk.getSummaryIfDone();
					if(summary.lastKey == null) {
						toSet = true;
					}
				} catch (RuntimeException e) {
					// all the "get" calls will end here if there was a native-level error
					promise.setError(e);
					return;
				} catch (Error e) {
					promise.setError(e);
					throw e;
				}
				if(toSet) {
					promise.set(Boolean.FALSE);
					return;
				}
				try {
					synchronized(AsyncRangeIterator.this) {
						fetchOutstanding = false;

						// adjust the total number of rows we should ever fetch
						rowsRemaining -= summary.keyCount;

						// set up the next fetch
						if(reverse) {
							end = KeySelector.firstGreaterOrEqual(summary.lastKey);
						} else {
							begin = KeySelector.firstGreaterThan(summary.lastKey);
						}

						RangeResult data = fetchingChunk.getIfDone();
						// If this is the first fetch or the main chunk is exhausted
						if(chunk == null || index == chunk.values.size()) {
							nextChunk = null;
							chunk = data;
							index = 0;
						} else {
							nextChunk = data;
						}
					}
				} catch (RuntimeException e) {
					// all the "get" calls will end here if there was a native-level error
					promise.setError(e);
					return;
				} catch (Error e) {
					promise.setError(e);
					throw e;
				}
				promise.set(Boolean.TRUE);
			}
		};

		private synchronized void startNextFetch() {
			if(fetchOutstanding)
				throw new IllegalStateException("Reentrant call not allowed"); // This is not be called reentrantly
			if(isCancelled)
				return;

			if(mainChunkIsTheLast())
				return;

			fetchOutstanding = true;
			nextChunk = null;

			FutureResults fetchingChunk = tr.getRange_internal(begin, end,
					rowsLimited ? rowsRemaining : 0, 0, streamingMode.code(),
					++iteration, snapshot, reverse);

			SettableFuture<Boolean> promise = new SettableFuture<Boolean>(tr.getExecutor());
			nextFuture = promise;
			promise.onCancelledCancel(fetchingChunk);
			fetchingChunk.onReady(new FetchComplete(fetchingChunk, promise));
		}

		@Override
		public synchronized Future<Boolean> onHasNext() {
			if(isCancelled)
				throw new CancellationException();

			// This will only happen before the first fetch has completed
			if(chunk == null) {
				return nextFuture;
			}

			// We have a chunk and are still working though it
			if(index < chunk.values.size()) {
				return new ReadyFuture<Boolean>(true, tr.getExecutor());
			}

			// If we are at the end of the current chunk there is either:
			//   - no more data -or-
			//   - we are already fetching the next block
			return mainChunkIsTheLast() ?
					new ReadyFuture<Boolean>(false, tr.getExecutor()) :
					nextFuture;
		}

		@Override
		public boolean hasNext() {
			return onHasNext().get();
		}

		// moves to the last position in the current chunk
		/*public synchronized void consumeAll() {
			index = chunk.values.size() - 1;
		}*/

		@Override
		public KeyValue next() {
			Future<Boolean> nextFuture;
			synchronized(this) {
				if(isCancelled)
					throw new CancellationException();

				// at least the first chunk has been fetched and there is at least one
				//  available result
				if(chunk != null && index < chunk.values.size()) {
					// If this is the first call to next() on a chunk, then we will want to
					//  start fetching the data for the next block
					boolean initialNext = index == 0;

					KeyValue result = chunk.values.get(index);
					prevKey = result.getKey();
					index++;

					// If this is the first call to next() on a chunk there cannot
					//  be another waiting, since we could not have issued a request
					assert(!(initialNext && nextChunk != null));

					// we are at the end of the current chunk and there is more to be had already
					if(index == chunk.values.size() && nextChunk != null) {
						index = 0;
						chunk = nextChunk;
						nextChunk = null;
					}

					if(initialNext) {
						startNextFetch();
					}

					return result;
				}

				nextFuture = onHasNext();
			}

			// If there was no result ready then we need to wait on the future
			//  and return the proper result, throwing if there are no more elements
			return nextFuture.map(NEXT_MAPPER).get();
		}

		@Override
		public synchronized void remove() {
			if(prevKey == null)
				throw new IllegalStateException("No value has been fetched from database");

			tr.clear(prevKey);
		}

		@Override
		public synchronized void cancel() {
			isCancelled = true;
			nextFuture.cancel();
		}

		@Override
		public void dispose() {
			cancel();
		}

		private final Function<Boolean, KeyValue> NEXT_MAPPER = new Function<Boolean, KeyValue>() {
			@Override
			public KeyValue apply(Boolean o) {
				if(o)
					return next();
				throw new NoSuchElementException();
			}
		};
	}
}