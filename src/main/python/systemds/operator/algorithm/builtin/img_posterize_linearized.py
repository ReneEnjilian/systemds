# -------------------------------------------------------------
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
# -------------------------------------------------------------

# Autogenerated By   : src/main/python/generator/generator.py
# Autogenerated From : scripts/builtin/img_posterize_linearized.dml

from typing import Dict, Iterable

from systemds.operator import OperationNode, Matrix, Frame, List, MultiReturn, Scalar
from systemds.utils.consts import VALID_INPUT_TYPES


def img_posterize_linearized(img_in: Matrix,
                             bits: int):
    """
     The Linearized Image Posterize function limits pixel values to 2^bits different values in the range [0, 255].
     Assumes the input image can attain values in the range [0, 255].
    
    
    
    :param img_in: Row linearized input images as 2D matrix
    :param bits: The number of bits keep for the values.
        1 means black and white, 8 means every integer between 0 and 255.
    :return: Row linearized output images as 2D matrix
    """

    params_dict = {'img_in': img_in, 'bits': bits}
    return Matrix(img_in.sds_context,
        'img_posterize_linearized',
        named_input_nodes=params_dict)
