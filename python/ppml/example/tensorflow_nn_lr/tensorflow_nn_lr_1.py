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

import logging
from typing import List
import numpy as np
import pandas as pd
import click

import tensorflow as tf
from tensorflow import Tensor
from tensorflow.keras.layers import Dense
from tensorflow.keras import Model,Input
from bigdl.ppml.fl.estimator import Estimator
from bigdl.ppml.fl.nn.fl_context import init_fl_context
from bigdl.ppml.fl.psi.psi_client import PSI

fmt = '%(asctime)s %(levelname)s {%(module)s:%(lineno)d} - %(message)s'
logging.basicConfig(format=fmt, level=logging.INFO)

class LocalModel(Model):
    def __init__(self) -> None:
        super().__init__()
        self.dense = Dense(1)

    def call(self, x):
        x = self.dense(x)
        return x


class ServerModel(Model):
    def __init__(self) -> None:
        super().__init__()
        self.sigmoid = tf.nn.sigmoid

    def forward(self, x: List[Tensor]):
        x = tf.stack(x)
        x = tf.reduce_sum(x,0) # above two act as interactive layer, CAddTable
        x = self.sigmoid(x)
        return x



@click.command()
@click.option('--load_model', default=False)
@click.option('--data_path', default="./data/diabetes-vfl-1.csv")
def run_client(load_model, data_path):
    init_fl_context(1)
    df_train = pd.read_csv(data_path)   
    
    df_train['ID'] = df_train['ID'].astype(str)
    psi = PSI()
    intersection = psi.get_intersection(list(df_train['ID']))
    df_train = df_train[df_train['ID'].isin(intersection)]

    df_x = df_train.drop('Outcome', 1)
    df_y = df_train['Outcome']
    
    x = df_x.to_numpy(dtype="float32")
    y = np.expand_dims(df_y.to_numpy(dtype="float32"), axis=1)
    
    loss_fn = tf.keras.losses.BinaryCrossentropy()

    if load_model:
        model = tf.keras.models.load_model('/tmp/tensorflow_client_model_1.pt')
        ppl = Estimator.from_keras(client_model=model,
                                   loss_fn=loss_fn,
                                   optimizer_cls=tf.keras.optimizers.SGD,
                                   optimizer_args={'learning_rate':1e-4},
                                   server_model_path='/tmp/tensorflow_server_model',
                                   client_model_path='/tmp/tensorflow_client_model_1.pt')
        ppl.load_server_model('/tmp/tensorflow_server_model')
        response = ppl.fit(x, y, 5)
    else:
        model = LocalModel()
        
        server_model = ServerModel()
        ppl = Estimator.from_keras(client_model=model,
                                   loss_fn=loss_fn,
                                   optimizer_cls=tf.keras.optimizers.SGD,
                                   optimizer_args={'learning_rate':1e-4},
                                   server_model=server_model,
                                   server_model_path='/tmp/tensorflow_server_model',
                                   client_model_path='/tmp/tensorflow_client_model_1.pt')
        response = ppl.fit(x, y, 5)
    result = ppl.predict(x)
    print(result[:5])

if __name__ == '__main__':
    run_client()