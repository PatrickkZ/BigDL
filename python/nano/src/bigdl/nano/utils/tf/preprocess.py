#
# Copyright 2016 The BigDL Authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import warnings
from typing import Sequence, Union, Optional

from bigdl.nano.utils.common import invalidInputError

import tensorflow as tf


def tensor_spec_to_shape(tensor_specs: Union[tf.TensorSpec, Sequence[tf.TensorSpec]]):
    """Convert TensorSpec(s) to shape(s)."""
    if isinstance(tensor_specs, Sequence):
        return tuple(spec.shape for spec in tensor_specs)
    else:
        return tensor_specs.shape


def fake_tensor_from_spec(tensor_specs: Union[tf.TensorSpec, Sequence[tf.TensorSpec]]):
    """Fake `Tensor`(s) from `TensorSpec`(s)."""
    if isinstance(tensor_specs, tf.TensorSpec):
        shape = tensor_specs.shape
        dtype = tensor_specs.dtype
        shape = tuple(dim if dim is not None else 1 for dim in shape)
        if shape == () and dtype == tf.bool:
            # This may be the `training` parameter, we should assume it is False
            return False
        return tf.ones(shape=shape, dtype=dtype)
    else:
        return [fake_tensor_from_spec(spec) for spec in tensor_specs]


def try_compute_output_shape(model: tf.keras.Model,
                             input_spec: Optional[Union[tf.TensorSpec, Sequence[tf.TensorSpec]]],
                             try_fake_inference: bool = True):
    """Try to compute model's output shape."""
    if hasattr(model, "input_shape"):
        # Sequential and functional API model has
        # `input_shape` and `output_shape` attributes
        return model.output_shape
    elif input_spec is not None:
        try:
            input_shape = tensor_spec_to_shape(input_spec)
            return model.compute_output_shape(input_shape)
        except Exception as _e:
            if try_fake_inference:
                inputs = fake_tensor_from_spec(input_spec)
                _ = model(inputs)
            return None
    else:
        invalidInputError(False,
                          "Subclassed model must specify `input_spec` parameter.")
