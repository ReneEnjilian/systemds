/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.runtime.compress.estim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysds.runtime.compress.CompressionSettings;
import org.apache.sysds.runtime.compress.colgroup.AColGroup.CompressionType;
import org.apache.sysds.runtime.compress.utils.ABitmap;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.util.CommonThreadPool;

/**
 * Main abstract class for estimating size of compressions on columns.
 */
public abstract class CompressedSizeEstimator {
	protected static final Log LOG = LogFactory.getLog(CompressedSizeEstimator.class.getName());

	/** The Matrix Block to extract the compression estimates from */
	protected MatrixBlock _data;
	/** The number of rows in the matrix block, extracted to a field because the matrix could be transposed */
	protected int _numRows;
	/** The number of columns in the matrix block, extracted to a field because the matrix could be transposed */
	protected int _numCols;
	/** The compression settings to use, for estimating the size, and compress the ColGroups. */
	protected final CompressionSettings _compSettings;

	/**
	 * Boolean specifying if the _data is in transposed format. This is used to select the correct readers for the
	 * extraction of bitmaps for the columns.
	 */
	protected boolean _transposed = false;

	/**
	 * Main Constructor for Compression Estimator.
	 * 
	 * protected because the factory should be used to construct the CompressedSizeEstimator
	 * 
	 * @param data         The matrix block to extract information from
	 * @param compSettings The Compression settings used.
	 */
	protected CompressedSizeEstimator(MatrixBlock data, CompressionSettings compSettings, boolean transposed) {
		_data = data;
		_transposed = transposed;
		_numRows = _transposed ? _data.getNumColumns() : _data.getNumRows();
		_numCols = _transposed ? _data.getNumRows() : _data.getNumColumns();
		_compSettings = compSettings;
	}

	/**
	 * Multi threaded version of extracting Compression Size info
	 * 
	 * @param k The concurrency degree.
	 * @return The Compression Size info of each Column compressed isolated.
	 */
	public CompressedSizeInfo computeCompressedSizeInfos(int k) {
		List<CompressedSizeInfoColGroup> sizeInfos = Arrays.asList(estimateIndividualColumnGroupSizes(k));
		return new CompressedSizeInfo(sizeInfos);
	}

	private CompressedSizeInfoColGroup[] estimateIndividualColumnGroupSizes(int k) {
		return (k > 1) ? CompressedSizeInfoColGroup(_numCols, k) : CompressedSizeInfoColGroup(_numCols);
	}

	/**
	 * Method used for compressing into one type of colGroup
	 * 
	 * @return CompressedSizeInfo on a compressed colGroup compressing the entire matrix into a single colGroup type.
	 */
	public CompressedSizeInfoColGroup estimateCompressedColGroupSize() {
		int[] colIndexes = makeColIndexes();
		return estimateCompressedColGroupSize(colIndexes);
	}

	/**
	 * Abstract method for extracting Compressed Size Info of specified columns, together in a single ColGroup
	 * 
	 * @param colIndexes The Colums to group together inside a ColGroup
	 * @return The CompressedSizeInformation associated with the selected ColGroups.
	 */
	public abstract CompressedSizeInfoColGroup estimateCompressedColGroupSize(int[] colIndexes);

	/**
	 * Method used to extract the CompressedSizeEstimationFactors from an constructed UncompressedBitmap. Note this
	 * method works both for the sample based estimator and the exact estimator, since the bitmap, can be extracted from
	 * a sample or from the entire dataset.
	 * 
	 * @param ubm        The UncompressedBitmap, either extracted from a sample or from the entier dataset
	 * @param colIndexes The columns that is compressed together.
	 * @return The size factors estimated from the Bit Map.
	 */
	public EstimationFactors estimateCompressedColGroupSize(ABitmap ubm, int[] colIndexes) {
		return EstimationFactors.computeSizeEstimationFactors(ubm,
			_compSettings.validCompressions.contains(CompressionType.RLE), _numRows, colIndexes);
	}

	private CompressedSizeInfoColGroup[] CompressedSizeInfoColGroup(int clen) {
		CompressedSizeInfoColGroup[] ret = new CompressedSizeInfoColGroup[clen];
		for(int col = 0; col < clen; col++)
			ret[col] = estimateCompressedColGroupSize(new int[] {col});
		return ret;
	}

	private CompressedSizeInfoColGroup[] CompressedSizeInfoColGroup(int clen, int k) {
		try {
			ExecutorService pool = CommonThreadPool.get(k);
			ArrayList<SizeEstimationTask> tasks = new ArrayList<>();
			for(int col = 0; col < clen; col++)
				tasks.add(new SizeEstimationTask(this, col));
			List<Future<CompressedSizeInfoColGroup>> rtask = pool.invokeAll(tasks);
			ArrayList<CompressedSizeInfoColGroup> ret = new ArrayList<>();
			for(Future<CompressedSizeInfoColGroup> lrtask : rtask)
				ret.add(lrtask.get());
			pool.shutdown();
			return ret.toArray(new CompressedSizeInfoColGroup[0]);
		}
		catch(InterruptedException | ExecutionException e) {
			return CompressedSizeInfoColGroup(clen);
		}
	}

	private static class SizeEstimationTask implements Callable<CompressedSizeInfoColGroup> {
		private final CompressedSizeEstimator _estimator;
		private final int _col;

		protected SizeEstimationTask(CompressedSizeEstimator estimator, int col) {
			_estimator = estimator;
			_col = col;
		}

		@Override
		public CompressedSizeInfoColGroup call() {
			return _estimator.estimateCompressedColGroupSize(new int[] {_col});
		}
	}

	private int[] makeColIndexes() {
		int[] colIndexes = new int[_numCols];
		for(int i = 0; i < _numCols; i++) {
			colIndexes[i] = i;
		}
		return colIndexes;
	}

	public MatrixBlock getSample() {
		return _data;
	}
}
