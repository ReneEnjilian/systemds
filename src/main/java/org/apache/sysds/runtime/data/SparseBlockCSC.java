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

package org.apache.sysds.runtime.data;

import java.util.Arrays;
import java.util.Iterator;

public class SparseBlockCSC extends SparseBlock{

	private static final long serialVersionUID = -8020198259526080455L;
	private int[] _ptr = null;       //column pointer array (size: clen+1)
	private int[] _indexes = null;   //row index array (size: >=nnz)
	private double[] _values = null; //value array (size: >=nnz)
	private int _size = 0;           //actual number of nnz
	private int _rlen = -1;			 // number of rows
	private int _clenInferred = -1;

	public SparseBlockCSC(int clen) {
		this(clen, INIT_CAPACITY);
	}

	public SparseBlockCSC(int clen, int capacity) {
		_ptr = new int[clen+1]; //ix0=0
		_indexes = new int[capacity];
		_values = new double[capacity];
		_size = 0;
	}

	public SparseBlockCSC(int clen, int capacity, int size){
		_ptr = new int[clen+1]; //ix0=0
		_indexes = new int[capacity];
		_values = new double[capacity];
		_size = size;
	}

	public SparseBlockCSC(int[] rowPtr, int[] rowInd, double[] values, int nnz){
		_ptr = rowPtr;
		_indexes = rowInd;
		_values = values;
		_size = nnz;
	}

	public SparseBlockCSC(SparseBlock sblock, int clen) {
		_clenInferred = clen;
		_rlen = sblock.numRows();
		_size = (int) sblock.size();
		initialize(sblock);
	}

	public SparseBlockCSC(SparseBlock sblock) {
		inferNumCol(sblock);
		_rlen = sblock.numRows();
		_size = (int) sblock.size();
		initialize(sblock);
	}

	private void initialize(SparseBlock sblock){

		if( _size > Integer.MAX_VALUE )
			throw new RuntimeException("SparseBlockCSC supports nnz<=Integer.MAX_VALUE but got "+_size);

		//special case SparseBlockCSC
		if( sblock instanceof SparseBlockCSC ) {
			SparseBlockCSC originalCSC = (SparseBlockCSC)sblock;
			_ptr = Arrays.copyOf(originalCSC._ptr, originalCSC.numCols()+1);
			_indexes = Arrays.copyOf(originalCSC._indexes, originalCSC._size);
			_values = Arrays.copyOf(originalCSC._values, originalCSC._size);
		}

		//special case SparseBlockMCSC
		else if( sblock instanceof SparseBlockMCSC ){
			SparseBlockMCSC originalMCSC = (SparseBlockMCSC) sblock;
			_ptr = new int[originalMCSC.numCols()+1];
			_ptr[0] = 0;
			_values = new double[(int) originalMCSC.size()];
			_indexes = new int[(int)originalMCSC.size()];
			int ptrPos = 1;
			int valPos = 0;
			SparseRow columns[] = originalMCSC.getCols();
			for(SparseRow column : columns){
				int rowIdx[] = column.indexes();
				double vals[] = column.values();
				for(int i = 0; i<column.size(); i++){
					_indexes[valPos + i] = rowIdx[i];
					_values[valPos + i] = vals[i];
				}
				_ptr[ptrPos] = _ptr[ptrPos-1] + column.size();
				ptrPos++;
				valPos += column.size();
			}
		}

		// general case sparse block (CSR, MCSR, DCSR)
		else {
			int rlen = _rlen;
			int clen = _clenInferred;
			int nnz = _size; // total number of non-zero elements

			// Step 1: Count non-zero elements per column
			int[] colCounts = new int[clen];
			for (int i = 0; i < rlen; i++) {
				if (!sblock.isEmpty(i)) {
					int alen = sblock.size(i);
					int apos = sblock.pos(i);
					int[] aix = sblock.indexes(i);
					for (int k = apos; k < apos + alen; k++) {
						int col = aix[k];
						colCounts[col]++;
					}
				}
			}

			// Step 2: Compute CSC pointer array (_ptr)
			_ptr = new int[clen + 1];
			_ptr[0] = 0;
			for (int j = 0; j < clen; j++) {
				_ptr[j + 1] = _ptr[j] + colCounts[j];
			}

			// Step 3: Initialize arrays for indexes and values
			_size = nnz;
			_indexes = new int[nnz];
			_values = new double[nnz];

			// Step 4: Initialize position trackers for each column
			int[] colPositions = new int[clen];
			System.arraycopy(_ptr, 0, colPositions, 0, clen);

			// Step 5: Fill CSC indexes (_indexes) and values (_values) arrays
			for (int i = 0; i < rlen; i++) {
				if (!sblock.isEmpty(i)) {
					int alen = sblock.size(i);
					int apos = sblock.pos(i);
					int[] aix = sblock.indexes(i);
					double[] avals = sblock.values(i);
					for (int k = apos; k < apos + alen; k++) {
						int col = aix[k];
						int pos = colPositions[col];
						_indexes[pos] = i;        // row index
						_values[pos] = avals[k];  // value
						colPositions[col]++;
					}
				}
			}
		}
	}

	public SparseBlockCSC(SparseRow[] cols, int nnz){

		int clen = cols.length;
		_clenInferred = clen;
		_ptr = new int[clen+1]; //ix0=0
		_indexes = new int[nnz];
		_values = new double[nnz];
		_size = nnz;

		for( int i=0, pos=0; i<clen; i++ ) {
			if( cols[i]!=null && !cols[i].isEmpty() ) {
				int alen = cols[i].size();
				int[] aix = cols[i].indexes();
				double[] avals = cols[i].values();
				System.arraycopy(aix, 0, _indexes, pos, alen);
				System.arraycopy(avals, 0, _values, pos, alen);
				pos += alen;
			}
			_ptr[i+1]=pos;
		}
	}

	public SparseBlockCSC(int cols, int[] rowInd, int[] colInd, double[] values){
		int nnz = values.length;
		_ptr = new int[cols + 1];
		_indexes = Arrays.copyOf(rowInd, rowInd.length);
		_values = Arrays.copyOf(values, nnz);
		_size = nnz;
		_clenInferred = cols;

		//single-pass construction of pointers
		int clast = 0;
		for(int i=0; i<nnz; i++) {
			int c = colInd[i];
			if( clast < c )
				Arrays.fill(_ptr, clast+1, c+1, i);
			clast = c;
		}
		Arrays.fill(_ptr, clast+1, numCols()+1, nnz);

	}

	public SparseBlockCSC(int cols, int nnz, int[] rowInd){

		_clenInferred = cols;
		_ptr = new int[cols+1];
		_indexes = Arrays.copyOf(rowInd, nnz);
		_values = new double[nnz];
		Arrays.fill(_values, 1);
		_size = nnz;

		// fix and complete !


	}

	private void inferNumCol(SparseBlock sblock) {
		int[] indexes = null;
		if (sblock instanceof SparseBlockMCSR) {
			SparseRow[] origRows = ((SparseBlockMCSR) sblock).getRows();
			for(SparseRow row : origRows){
				if(row != null) {
					indexes = row.indexes();
					int max = Arrays.stream(indexes).max().getAsInt();
					if(max > _clenInferred)
						_clenInferred = max;
				}
			}
		}
		else if (sblock instanceof SparseBlockMCSC){
			_clenInferred = ((SparseBlockMCSC) sblock).getCols().length;
		}
		else if(sblock instanceof SparseBlockCSC) {
			_clenInferred = ((SparseBlockCSC) sblock).numCols();
		}
		// SparseBlockCSR and SparseBlockDCSR
		else {
			indexes = sblock.indexes(0);
			_clenInferred = Arrays.stream(indexes).max().getAsInt();
		}
		_clenInferred += 1;
	}



	@Override
	public void allocate(int r) {

	}

	@Override
	public void allocate(int r, int nnz) {

	}

	@Override
	public void allocate(int r, int ennz, int maxnnz) {

	}

	@Override
	public void compact(int r) {

	}

	@Override
	public int numRows() {
		return 6;
	}

	public int numCols() {
		return _ptr.length - 1;
	}

	@Override
	public boolean isThreadSafe() {
		return false;
	}

	@Override
	public boolean isContiguous() {
		return false;
	}

	@Override
	public boolean isAllocated(int r) {
		return false;
	}

	@Override
	public void reset() {

	}

	@Override
	public void reset(int ennz, int maxnnz) {

	}

	@Override
	public void reset(int r, int ennz, int maxnnz) {

	}

	@Override
	public long size() {
		return _ptr[_ptr.length-1];
	}

	@Override
	public int size(int r) {
		return _ptr[r+1] - _ptr[r];
	}

	@Override
	public long size(int rl, int ru) {
		return 0;
	}

	@Override
	public long size(int rl, int ru, int cl, int cu) {
		return 0;
	}

	@Override
	public boolean isEmpty(int r) {
		return false;
	}

	@Override
	public boolean checkValidity(int rlen, int clen, long nnz, boolean strict) {
		return false;
	}

	@Override
	public long getExactSizeInMemory() {
		return 0;
	}

	@Override
	public int[] indexes(int r) {
		return _indexes;
	}

	public int[] indexesCol(int c){
		return _indexes;
	}

	@Override
	public double[] values(int r) {
		return _values;
	}

	public double[] valuesCol(int c) {
		return _values;
	}


	@Override
	public int pos(int r) {
		return _ptr[r];
	}

	public int posCol(int c) {
		return _ptr[c];
	}

	@Override
	public boolean set(int r, int c, double v) {
		return false;
	}

	@Override
	public void set(int r, SparseRow row, boolean deep) {

	}

	@Override
	public boolean add(int r, int c, double v) {
		return false;
	}

	@Override
	public void append(int r, int c, double v) {

	}

	@Override
	public void setIndexRange(int r, int cl, int cu, double[] v, int vix, int vlen) {

	}

	@Override
	public void setIndexRange(int r, int cl, int cu, double[] v, int[] vix, int vpos, int vlen) {

	}

	@Override
	public void deleteIndexRange(int r, int cl, int cu) {

	}

	@Override
	public void sort() {

	}

	@Override
	public void sort(int r) {

	}

	@Override
	public double get(int r, int c) {
		return 0;
	}

	@Override
	public SparseRow get(int r) {
		return null;
	}

	@Override
	public int posFIndexLTE(int r, int c) {
		return 0;
	}

	@Override
	public int posFIndexGTE(int r, int c) {
		return 0;
	}

	@Override
	public int posFIndexGT(int r, int c) {
		return 0;
	}

	@Override
	public Iterator<Integer> getNonEmptyRowsIterator(int rl, int ru) {
		return null;
	}

	@Override
	public String toString() {
		return null;
	}


}
