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
# Autogenerated From : scripts/builtin/img_brightness_linearized.dml

from typing import Dict, Iterable

from systemds.operator import OperationNode, Matrix, Frame, List, MultiReturn, Scalar
from systemds.utils.consts import VALID_INPUT_TYPES


def img_brightness_linearized(img_in: Matrix,
                              value: float,
                              channel_max: int):
    """
     The img_brightness_linearized-function is an image data augmentation function. It changes the brightness of one or multiple images.
    
    
    
    :param img_in: Input matrix/image (can represent multiple images every row of the matrix represents a linearized image)
    :param value: The amount of brightness to be changed for the image
    :param channel_max: Maximum value of the brightness of the image
    :return: Output matrix/images  (every row of the matrix represents a linearized image)
    """

    params_dict = {'img_in': img_in, 'value': value, 'channel_max': channel_max}
    return Matrix(img_in.sds_context,
        'img_brightness_linearized',
        named_input_nodes=params_dict)
