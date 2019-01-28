#!/bin/bash
mkdir -p data/aimes-house-prices

pushd data

wget https://s3.us-east-2.amazonaws.com/tech.public.data/house-prices-advanced-regression-techniques.zip

unzip house-prices-advanced-regression-techniques.zip -d aimes-house-prices

pushd aimes-house-prices

# Of course the files have incorrect permissions...

chmod 644 *

popd

popd

wget https://s3.us-east-2.amazonaws.com/tech.public.data/aimes-initial-results.nippy
