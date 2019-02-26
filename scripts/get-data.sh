#!/bin/bash
mkdir -p data/ames-house-prices

pushd data

wget https://s3.us-east-2.amazonaws.com/tech.public.data/house-prices-advanced-regression-techniques.zip

unzip house-prices-advanced-regression-techniques.zip -d ames-house-prices

pushd ames-house-prices

# Of course the files have incorrect permissions...

chmod 644 *

popd

popd


wget https://s3.us-east-2.amazonaws.com/tech.public.data/ames-final-results.nippy
wget https://s3.us-east-2.amazonaws.com/tech.public.data/ames-one-hot-results.nippy
wget https://s3.us-east-2.amazonaws.com/tech.public.data/ames-skew-fix-results.nippy
