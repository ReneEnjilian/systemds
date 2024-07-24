import os
import numpy as np
import pandas as pd

def gen_data_dense(rows, cols, path, chunk_size=10000):
    """
    Generate a dense matrix and save it to a CSV file.

    Parameters:
    rows (int): Number of rows.
    cols (int): Number of columns.
    path (str): Path to save the generated matrix.
    chunk_size (int): Number of rows per chunk to generate and save.
    """
    with open(path, 'w') as f:
        for start_row in range(0, rows, chunk_size):
            end_row = min(start_row + chunk_size, rows)
            chunk_rows = end_row - start_row

            # Generate a dense matrix with random values
            chunk_matrix = np.random.random((chunk_rows, cols))

            # Save the chunk to the CSV file
            np.savetxt(f, chunk_matrix, delimiter=',')
            # np.savetxt(f, chunk_matrix, delimiter=',', fmt='%.10f')
            print(f"Saved chunk {start_row} to {end_row} to {path}")

def main():
    # Hardcoded parameters
    dense_gb = 0.0001

    current_directory = os.getcwd()
    target_directory = os.path.abspath(os.path.join(current_directory, '../../../../src/test/resources/datasets/slab/dense'))
    os.makedirs(target_directory, exist_ok=True)

    k = int(np.ceil((dense_gb * 1e9) / float(8 * 100)))

    # Paths for saving the matrices
    mpath_tall = os.path.join(target_directory, 'M_dense_tall.csv')
    mpath_wide = os.path.join(target_directory, 'M_dense_wide.csv')

    # Generate and save dense matrices
    gen_data_dense(k, 100, mpath_tall)
    gen_data_dense(100, k, mpath_wide)

if __name__ == "__main__":
    main()
