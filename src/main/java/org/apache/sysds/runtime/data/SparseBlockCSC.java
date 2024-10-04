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

import org.apache.sysds.runtime.util.SortUtils;
import org.apache.sysds.utils.MemoryEstimates;

import java.io.DataInput;
import java.io.IOException;
import java.util.*;

/**
 * SparseBlock implementation that realizes a traditional 'compressed sparse column'
 * representation, where the entire sparse block is stored as three arrays: ptr
 * of length clen+1 to store offsets per column, and indexes/values of length nnz
 * to store row indexes and values of non-zero entries. This format is very
 * memory efficient for sparse (but not ultra-sparse) matrices and provides very
 * good performance for common operations, partially due to lower memory bandwidth
 * requirements. However, this format is slow on incremental construction (because
 * it does not allow append/sort per row) without reshifting. For these types of
 * operations, the 'modified compressed sparse row/column' representations are better
 * suited since they are update friendlier. Further, this representation implements
 * both the row oriented and the column oriented one. By implementing the row-oriented
 * API, we ensure interoperability with existing operators.
 * Finally, the total nnz is limited to INTEGER_MAX, whereas for SparseBlockMCSR
 * only the nnz per row are limited to INTEGER_MAX.
 *
 */

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

		//single-pass construction of col pointers
		//and copy of row indexes if necessary
		for(int i=0, pos=0; i<cols; i++) {
			if( rowInd[i] >= 0 ) {
				if( cols > nnz )
					_indexes[pos] = rowInd[i];
				pos++;
			}
			_ptr[i+1] = pos;
		}
	}

	/**
	 * Initializes the CSC sparse block from an ordered input
	 * stream of ultra-sparse ijv triples.
	 *
	 * @param nnz number of non-zeros to read
	 * @param in data input stream of ijv triples, ordered by column and row indices
	 * @throws IOException if deserialization error occurs
	 */
	public void initUltraSparse(int nnz, DataInput in)
		throws IOException
	{
		// Allocate space if necessary
		if (_values.length < nnz)
			resize(newCapacity(nnz));

		// Read ijv triples, append and update pointers
		int clast = 0;
		for (int i = 0; i < nnz; i++) {
			int r = in.readInt();
			int c = in.readInt();
			double v = in.readDouble();

			if (clast < c)
				Arrays.fill(_ptr, clast + 1, c + 1, i);
			clast = c;

			_indexes[i] = r;   // Row indices in CSC
			_values[i] = v;
		}
		Arrays.fill(_ptr, clast + 1, numCols() + 1, nnz);

		// Update meta data
		_size = nnz;
	}

	/**
	 * Initializes the CSC sparse block from an ordered input
	 * stream of sparse columns (colnnz, iv-pairs*).
	 *
	 * @param clen number of columns
	 * @param nnz number of non-zeros to read
	 * @param in data input stream of sparse columns, ordered by column index
	 * @throws IOException if deserialization error occurs
	 */
	public void initSparse(int clen, int nnz, DataInput in)
		throws IOException
	{
		// Allocate space if necessary
		if (_values.length < nnz) {
			resize(newCapacity(nnz));
			System.out.println("hallo");
		}
		// Read sparse columns, append and update pointers
		_ptr[0] = 0;
		for (int c = 0, pos = 0; c < clen; c++) {
			int lnnz = in.readInt();  // Number of non-zeros in column c
			for (int j = 0; j < lnnz; j++, pos++) {
				_indexes[pos] = in.readInt();   // Row index
				_values[pos] = in.readDouble(); // Value
			}
			_ptr[c + 1] = pos;
		}

		// Update meta data
		_size = nnz;
	}

	/**
	 * Get the estimated in-memory size of the sparse block in CSC
	 * with the given dimensions w/o accounting for overallocation.
	 *
	 * @param nrows number of rows
	 * @param ncols number of columns
	 * @param sparsity sparsity ratio
	 * @return memory estimate
	 */
	public static long estimateSizeInMemory(long nrows, long ncols, double sparsity) {
		double lnnz = Math.max(INIT_CAPACITY, Math.ceil(sparsity*nrows*ncols));

		//32B overhead per array, int arr in nrows, int/double arr in nnz
		double size = 16 + 4 + 4;                            //object + int field + padding
		size += MemoryEstimates.intArrayCost(nrows+1);       //ptr array (row pointers)
		size += MemoryEstimates.intArrayCost((long) lnnz);   //indexes array (column indexes)
		size += MemoryEstimates.doubleArrayCost((long) lnnz);//values array (non-zero values)

		//robustness for long overflows
		return (long) Math.min(size, Long.MAX_VALUE);
	}

	/**
	 * Get raw access to underlying array of column pointers
	 * For use in GPU code
	 * @return array of column pointers
	 */
	public int[] colPointers() {
		return _ptr;
	}

	/**
	 * Get raw access to underlying array of row indices
	 * For use in GPU code
	 * @return array of row indexes
	 */
	public int[] indexes() {
		return indexes(0);
	}

	/**
	 * Get raw access to underlying array of values
	 * For use in GPU code
	 * @return array of values
	 */
	public double[] values() {
		return values(0);
	}


	///////////////////
	//SparseBlock implementation



	@Override
	public void allocate(int r) {
		//do nothing everything preallocated
	}

	@Override
	public void allocate(int r, int nnz) {
		//do nothing everything preallocated
	}

	@Override
	public void allocate(int r, int ennz, int maxnnz) {
		//do nothing everything preallocated
	}

	@Override
	public void compact(int r) {
		//do nothing everything preallocated
	}

	@Override
	public int numRows() {
		if(_rlen > -1)
			return _rlen;
		else {
			int rlen = Arrays.stream(_indexes).max().getAsInt();
			_rlen = rlen;
			return rlen;
		}
	}

	/**
	 * Get the number of columns in the CSC block
	 * @return number of columns
	 */
	public int numCols() {
		return _ptr.length - 1;
	}

	@Override
	public boolean isThreadSafe() {
		return false;
	}

	@Override
	public boolean isContiguous() {
		return true;
	}

	@Override
	public boolean isAllocated(int r) {
		return true;
	}

	@Override
	public void reset() {
		if( _size > 0 ) {
			Arrays.fill(_ptr, 0);
			_size = 0;
		}
	}

	@Override
	public void reset(int ennz, int maxnnz) {
		if( _size > 0 ) {
			Arrays.fill(_ptr, 0);
			_size = 0;
		}
	}

	@Override
	public void reset(int r, int ennz, int maxnnz) {
		ArrayList<Integer> cols = new ArrayList();
		ArrayList<Integer> posIdx = new ArrayList<>();
		// find columns containing marked row index and
		// position in indexes
		for(int i = 0; i<_ptr.length-1; i++){
			for(int j = _ptr[i]; j < _ptr[i+1]; j++){
				if(_indexes[j] == r) {
					cols.add(i);
					posIdx.add(j);
				}
			}
		}
		// reduce pointer array.
		for(int c : cols){
			decrPtr(c+1);
		}
		// adapt indexes and values
		for(int i = posIdx.size()-1; i >=0; i--){
			if(posIdx.get(i) < _size-1) {
				System.arraycopy(_indexes, posIdx.get(i) + 1, _indexes, posIdx.get(i), _size - (posIdx.get(i) + 1));
				System.arraycopy(_values, posIdx.get(i) + 1, _values, posIdx.get(i), _size - (posIdx.get(i) + 1));
			}
		}
		_size -= posIdx.size();
	}

	public void resetCol(int c){
		int pos = posCol(c);
		int len = sizeCol(c);

		if( len > 0 ) {
			//overlapping array copy (shift rhs values left)
			System.arraycopy(_indexes, pos+len, _indexes, pos, _size-(pos+len));
			System.arraycopy(_values, pos+len, _values, pos, _size-(pos+len));
			_size -= len;
			decrPtr(c+1, len);
		}
	}

	@Override
	public long size() {
		return _ptr[_ptr.length-1];
	}

	@Override
	public int size(int r) {
		if(r < 0)
			throw new RuntimeException("Row index has to be zero or larger.");

		int nnz = 0;
		for(int i = 0; i<_size; i++){
			if(_indexes[i] == r)
				nnz++;
		}
		return nnz;
	}

	public int sizeCol(int c){
		return _ptr[c+1] - _ptr[c];
	}

	@Override
	public long size(int rl, int ru) {
		if(rl < 0 || ru > _rlen)
			throw new RuntimeException("Incorrect row boundaries.");

		int nnz = 0;
		int row = -1;
		for(int i = 0; i<_size; i++){
			row = _indexes[i];
			for(int j = rl; j < ru; j++){
				if(row == j){
					nnz++;
					break;
				}
			}
		}
		return nnz;
	}

	public long sizeCol(int cl, int cu){
		return _ptr[cu] - _ptr[cl];
	}

	@Override
	public long size(int rl, int ru, int cl, int cu) {
		long nnz = 0;
		for(int i = cl; i < cu; i++)
			if(!isEmptyCol(i)) {
				int start = internPosFIndexGTE(rl, i);
				int end = internPosFIndexLTE(ru - 1, i);
				nnz += (start != -1 && end != -1) ? (end - start + 1) : 0;
			}
		return nnz;
	}

	@Override
	public boolean isEmpty(int r) {
		boolean empty = true;
		for(int i = 0; i<_size; i++){
			if(_indexes[i] == r)
				return false;
		}
		return empty;
	}

	public boolean isEmptyCol(int c){
		return (_ptr[c+1] - _ptr[c] == 0);
	}

	@Override
	public boolean checkValidity(int rlen, int clen, long nnz, boolean strict) {
		//1. correct meta data
		if( rlen < 0 || clen < 0 ) {
			throw new RuntimeException("Invalid block dimensions: "+rlen+" "+clen);
		}

		//2. correct array lengths
		if(_size != nnz && _ptr.length < clen+1 && _values.length < nnz && _indexes.length < nnz ) {
			throw new RuntimeException("Incorrect array lengths.");
		}

		//3. non-decreasing row pointers
		for( int i=1; i<clen; i++ ) {
			if(_ptr[i-1] > _ptr[i] && strict)
				throw new RuntimeException("Column pointers are decreasing at column: "+i
					+ ", with pointers "+_ptr[i-1]+" > "+_ptr[i]);
		}

		//4. sorted row indexes per column
		for( int i=0; i<clen; i++ ) {
			int apos = posCol(i);
			int alen = sizeCol(i);
			for( int k=apos+1; k<apos+alen; k++)
				if( _indexes[k-1] >= _indexes[k] )
					throw new RuntimeException("Wrong sparse column ordering: "
						+ k + " "+_indexes[k-1]+" "+_indexes[k]);
			for( int k=apos; k<apos+alen; k++ )
				if( _values[k] == 0 )
					throw new RuntimeException("Wrong sparse column: zero at "
						+ k + " at row index " + _indexes[k]);
		}

		//5. non-existing zero values
		for( int i=0; i<_size; i++ ) {
			if( _values[i] == 0 ) {
				throw new RuntimeException("The values array should not contain zeros."
					+ " The " + i + "th value is "+_values[i]);
			}
		}

		//6. a capacity that is no larger than nnz times resize factor.
		int capacity = _values.length;
		if(capacity > nnz*RESIZE_FACTOR1 ) {
			throw new RuntimeException("Capacity is larger than the nnz times a resize factor."
				+ " Current size: "+capacity+ ", while Expected size:"+nnz*RESIZE_FACTOR1);
		}

		return true;
	}

	@Override
	public long getExactSizeInMemory() {
		//32B overhead per array, int arr in nrows, int/double arr in nnz
		double size = 16 + 4 + 4;                                //object + int field + padding
		size += MemoryEstimates.intArrayCost(_ptr.length);       //ptr array (row pointers)
		size += MemoryEstimates.intArrayCost(_indexes.length);   //indexes array (column indexes)
		size += MemoryEstimates.doubleArrayCost(_values.length); //values array (non-zero values)

		//robustness for long overflows
		return (long) Math.min(size, Long.MAX_VALUE);
	}

	@Override
	public int[] indexes(int r) {
		//Count elements per row
		int[] rowCounts = numElemPerRow();

		// Compute csr pointers
		int[] csrPtr = rowPointerAll();

		// Populate CSR indices array
		int[] csrIndices = new int[_size];
		// Temporary array to keep track of the current position in each row
		int[] currentPos = Arrays.copyOf(csrPtr, _rlen);

		for (int col = 0; col < numCols(); col++) {
			for (int i = _ptr[col]; i < _ptr[col + 1]; i++) {
				int row = _indexes[i];
				int pos = currentPos[row]++;
				csrIndices[pos] = col;
			}
		}

		return csrIndices;
	}

	public int[] indexesCol(int c){
		return _indexes;
	}

	@Override
	public double[] values(int r) {
		// Only use first _size elements for sorting
		Integer[] idx = new Integer[_size];
		for (int i = 0; i < _size; i++)
			idx[i] = i;

		// Sort indices based on corresponding index values
		Arrays.sort(idx, Comparator.comparingInt(i -> _indexes[i]));

		// Create values array sorted in row order
		double[] csrValues = new double[_size];
		for (int i = 0; i < _size; i++) {
			csrValues[i] = _values[idx[i]];
		}
		return csrValues;
	}

	public double[] valuesCol(int c) {
		return _values;
	}


	@Override
	public int pos(int r) {
		int nnz = 0;
		for(int i = 0; i<_size; i++){
			if(_indexes[i] < r)
				nnz++;
		}
		return nnz;
	}


	public int posCol(int c) {
		return _ptr[c];
	}


	@Override
	public boolean set(int r, int c, double v) {
		int pos = posCol(c);
		int len = sizeCol(c);

		//search for existing col index
		int index = Arrays.binarySearch(_indexes, pos, pos+len, r);
		if( index >= 0 ) {
			//delete/overwrite existing value (on value delete, we shift
			//left for (1) correct nnz maintenance, and (2) smaller size)
			if( v == 0 ) {
				shiftLeftAndDelete(index);
				decrPtr(c+1);
				return true; // nnz--
			}
			else {
				_values[index] = v;
				return false;
			}
		}

		//early abort on zero (if no overwrite)
		if( v==0 ) return false;

		//insert new index-value pair
		index = Math.abs( index+1 );
		if( _size==_values.length )
			resizeAndInsert(index, r, v);
		else
			shiftRightAndInsert(index, r, v);
		incrPtr(c+1);
		return true; // nnz++
	}

	@Override
	public void set(int r, SparseRow row, boolean deep) {
		double[] values = row.values();
		int[] colIndexes = row.indexes();
		for(int i = 0; i<row.size(); i++){
			set(r, colIndexes[i], values[i]);
		}
	}

	public void setCol(int c, SparseRow col, boolean deep){
		int pos = posCol(c);
		int len = sizeCol(c);
		int alen = col.size();
		int[] aix = col.indexes();
		double[] avals = col.values();

		//delete existing values if necessary
		if( len > 0 ) //incl size update
			deleteIndexRange(c, aix[pos], aix[pos+len-1]+1);

		//prepare free space (allocate and shift)
		int lsize = _size+alen;
		if( _values.length < lsize )
			resize(lsize);
		shiftRightByN(pos, alen); //incl size update
		incrPtr(c+1, alen);

		//copy input row into internal representation
		System.arraycopy(aix, 0, _indexes, pos, alen);
		System.arraycopy(avals, 0, _values, pos, alen);
	}

	@Override
	public boolean add(int r, int c, double v) {
		//early abort on zero
		if( v==0 ) return false;

		int pos = posCol(c);
		int len = sizeCol(c);

		//search for existing col index
		int index = Arrays.binarySearch(_indexes, pos, pos+len, r);
		if( index >= 0 ) {
			//add to existing value
			_values[index] += v;
			return false;
		}

		//insert new index-value pair
		index = Math.abs( index+1 );
		if( _size==_values.length )
			resizeAndInsert(index, r, v);
		else
			shiftRightAndInsert(index, r, v);
		incrPtr(c+1);
		return true; // nnz++
	}

	@Override
	public void append(int r, int c, double v) {
		//early abort on zero
		if( v==0 ) return;

		int pos = posCol(c);
		int len = sizeCol(c);
		if( pos+len == _size ) {
			//resize and append
			if( _size==_values.length )
				resize();
			insert(_size, r, v);
		}
		else {
			//resize, shift and insert
			if( _size==_values.length )
				resizeAndInsert(pos+len, r, v);
			else
				shiftRightAndInsert(pos+len, r, v);
		}
		incrPtr(c+1);

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
		for(int i = 0; i<numCols() && posCol(i)<_size; i++){
			sortCol(i);
		}
	}

	@Override
	public void sort(int r) {
		sort();
	}

	public void sortCol(int c){
		int pos = posCol(c);
		int len = sizeCol(c);

		if( len<=100 || !SortUtils.isSorted(pos, pos+len, _indexes) )
			SortUtils.sortByIndex(pos, pos+len, _indexes, _values);
	}

	@Override
	public double get(int r, int c) {
		if( isEmptyCol(c) )
			return 0;
		int pos = posCol(c);
		int len = sizeCol(c);

		//search for existing col index in [pos,pos+len)
		int index = Arrays.binarySearch(_indexes, pos, pos+len, r);
		return (index >= 0) ? _values[index] : 0;
	}

	@Override
	public SparseRow get(int r) {
		int rowSize = size(r);
		if(rowSize == 0)
			return new SparseRowScalar();

		//Create sparse row
		SparseRowVector row = new SparseRowVector(rowSize);

		for(int i = 0; i<_size; i++){
			if(_indexes[i] == r){
				//Search for index i in pointer array
				for(int j = 0; j<_ptr.length; j++){
					// two possible cases
					if(_ptr[j] < i && _ptr[j+1] > i){
						row.set(j, _values[i]);
					}
					else if(_ptr[j] == i && _ptr[j+1] > i){
						row.set(j, _values[i]);
						break;
					}
				}
			}
		}
		return row;
	}

	public SparseRow getCol(int c){
		if( isEmptyCol(c) )
			return new SparseRowScalar();
		int pos = posCol(c);
		int len = sizeCol(c);

		SparseRowVector col = new SparseRowVector(len);
		System.arraycopy(_indexes, pos, col.indexes(), 0, len);
		System.arraycopy(_values, pos, col.values(), 0, len);
		col.setSize(len);
		return col;
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
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("SparseBlockCSR: clen=");
		sb.append(numCols());
		sb.append(", nnz=");
		sb.append(size());
		sb.append("\n");
		final int colDigits = (int)Math.max(Math.ceil(Math.log10(numCols())),1) ;
		for(int i = 0; i < numCols(); i++) {
			// append column
			final int pos = posCol(i);
			final int len = sizeCol(i);
			if(pos < pos + len) {
				sb.append(String.format("%0"+colDigits+"d ", i));
				for(int j = pos; j < pos + len; j++) {
					if(_values[j] == (long) _values[j])
						sb.append(String.format("%"+colDigits+"d:%d", _indexes[j], (long)_values[j]));
					else
						sb.append(String.format("%"+colDigits+"d:%s", _indexes[j], Double.toString(_values[j])));
					if(j + 1 < pos + len)
						sb.append(" ");
				}
				sb.append("\n");
			}
		}

		return sb.toString();
	}

	@Override
	public Iterator<Integer> getNonEmptyRowsIterator(int rl, int ru) {
		return new NonEmptyRowsIteratorCSC(rl, ru);
	}

	public class NonEmptyRowsIteratorCSC implements Iterator<Integer> {
		private int _rpos;
		private final int _ru;
		private BitSet _rows = null;

		public NonEmptyRowsIteratorCSC(int rl, int ru) {
			_rpos = rl;
			_ru = ru;
			checkNonEmptyRows();
		}

		@Override
		public boolean hasNext() {
			while( _rpos<_ru && !_rows.get(_rpos) )
				_rpos++;
			return _rpos < _ru;
		}

		@Override
		public Integer next() {
			return _rpos++;
		}

		private void checkNonEmptyRows(){
			int rlen = numRows();
			_rows = new BitSet(rlen);
			for(int i = 0; i<_size; i++)
				_rows.set(_indexes[i]);
		}
	}

	public Iterator<Integer> getNonEmptyColumnsIterator(int cl, int cu) {
		return new SparseBlockCSC.NonEmptyColumnsIteratorCSC(cl, cu);
	}

	public class NonEmptyColumnsIteratorCSC implements Iterator<Integer> {
		private int _cpos;
		private final int _cu;

		public NonEmptyColumnsIteratorCSC(int cl, int cu) {
			_cpos = cl;
			_cu = cu;
		}

		@Override
		public boolean hasNext() {
			while(_cpos < _cu && isEmptyCol(_cpos)) {
				_cpos++;
			}
			return _cpos < _cu;
		}

		@Override
		public Integer next() {
			return _cpos++;
		}

	}

	@SuppressWarnings("unused")
	private class SparseNonEmptyColumnIterable implements Iterable<Integer> {
		private final int _cl; //column lower
		private final int _cu; //column upper

		protected SparseNonEmptyColumnIterable(int cl, int cu) {
			_cl = cl;
			_cu = cu;
		}

		@Override
		public Iterator<Integer> iterator() {
			//use specialized non-empty row iterators of sparse blocks
			return getNonEmptyColumnsIterator(_cl, _cu);
		}
	}



	///////////////////////////
	// private helper methods

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

	private int newCapacity(int minsize) {
		//compute new size until minsize reached
		double tmpCap = Math.max(_values.length, 1);
		while( tmpCap < minsize ) {
			tmpCap *= (tmpCap <= 1024) ?
				RESIZE_FACTOR1 : RESIZE_FACTOR2;
		}
		return (int)Math.min(tmpCap, Integer.MAX_VALUE);
	}

	private void resize() {
		//resize by at least by 1
		int newCap = newCapacity(_values.length+1);
		resizeCopy(newCap);
	}

	private void resize(int minsize) {
		int newCap = newCapacity(minsize);
		resizeCopy(newCap);
	}

	private void resizeCopy(int capacity) {
		//reallocate arrays and copy old values
		_indexes = Arrays.copyOf(_indexes, capacity);
		_values = Arrays.copyOf(_values, capacity);
	}

	private void resizeAndInsert(int ix, int r, double v) {
		//compute new size
		int newCap = newCapacity(_values.length+1);

		int[] oldindexes = _indexes;
		double[] oldvalues = _values;
		_indexes = new int[newCap];
		_values = new double[newCap];

		//copy lhs values to new array
		System.arraycopy(oldindexes, 0, _indexes, 0, ix);
		System.arraycopy(oldvalues, 0, _values, 0, ix);

		//copy rhs values to new array
		System.arraycopy(oldindexes, ix, _indexes, ix+1, _size-ix);
		System.arraycopy(oldvalues, ix, _values, ix+1, _size-ix);

		//insert new value
		insert(ix, r, v);
	}

	private void shiftRightAndInsert(int ix, int r, double v)  {
		//overlapping array copy (shift rhs values right by 1)
		System.arraycopy(_indexes, ix, _indexes, ix+1, _size-ix);
		System.arraycopy(_values, ix, _values, ix+1, _size-ix);

		//insert new value
		insert(ix, r, v);
	}

	private void shiftLeftAndDelete(int ix)
	{
		//overlapping array copy (shift rhs values left by 1)
		System.arraycopy(_indexes, ix+1, _indexes, ix, _size-ix-1);
		System.arraycopy(_values, ix+1, _values, ix, _size-ix-1);
		_size--;
	}

	private void shiftRightByN(int ix, int n)
	{
		//overlapping array copy (shift rhs values right by 1)
		System.arraycopy(_indexes, ix, _indexes, ix+n, _size-ix);
		System.arraycopy(_values, ix, _values, ix+n, _size-ix);
		_size += n;
	}

	private void shiftLeftByN(int ix, int n)
	{
		//overlapping array copy (shift rhs values left by n)
		System.arraycopy(_indexes, ix, _indexes, ix-n, _size-ix);
		System.arraycopy(_values, ix, _values, ix-n, _size-ix);
		_size -= n;
	}

	private void insert(int ix, int r, double v) {
		_indexes[ix] = r;
		_values[ix] = v;
		_size++;
	}


	private void incrPtr(int cl) {
		incrPtr(cl, 1);
	}

	private void incrPtr(int cl, int cnt) {
		int clen = numCols();
		for( int i=cl; i<clen+1; i++ )
			_ptr[i]+=cnt;
	}

	private void incrRowPtr(int rl, int[] csrPtr){
		incrRowPtr(rl, csrPtr, 1);
	}

	private void incrRowPtr(int rl, int[] csrPtr, int cnt){
		for(int i = rl; i<csrPtr.length; i++){
			csrPtr[i] += cnt;
		}
	}

	private void decrPtr(int cl) {
		decrPtr(cl, 1);
	}

	private void decrPtr(int cl, int cnt) {
		for( int i=cl; i<_ptr.length; i++ )
			_ptr[i]-=cnt;
	}
	
	private int[] numElemPerRow(){
		int rlen = numRows();
		int[] rowCount = new int[rlen];
		for(int i = 0; i < _size; i++){
			rowCount[_indexes[i]] += 1;
		}
		return rowCount;
	}

	private int[] rowPointerAll(){
		int rlen = numRows();
		int[] csrPtr = new int[rlen+1];
		csrPtr[0] = 0;
		for(int i = 0; i<_size; i++)
			incrRowPtr(_indexes[i]+1, csrPtr);

		return csrPtr;
	}

	private int internPosFIndexLTE(int r, int c) {
		int pos = posCol(c);
		int len = sizeCol(c);

		//search for existing row index in [pos,pos+len)
		int index = Arrays.binarySearch(_indexes, pos, pos+len, r);
		if( index >= 0  )
			return (index < pos+len) ? index : -1;

		//search lt row index (see binary search)
		index = Math.abs( index+1 );
		return (index-1 >= pos) ? index-1 : -1;
	}


	private int internPosFIndexGTE(int r, int c) {
		int pos = posCol(c);
		int len = sizeCol(c);

		//search for existing row index
		int index = Arrays.binarySearch(_indexes, pos, pos+len, r);
		if( index >= 0  )
			return (index < pos+len) ? index : -1;

		//search gt row index (see binary search)
		index = Math.abs( index+1 );
		return (index < pos+len) ? index : -1;
	}



}
