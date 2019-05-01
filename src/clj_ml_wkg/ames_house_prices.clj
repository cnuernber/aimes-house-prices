(ns clj-ml-wkg.ames-house-prices
  "See reference article:
  https://www.kaggle.com/juliencs/a-study-on-regression-applied-to-the-ames-dataset
  "
  (:require [tech.ml.dataset.pipeline
             :refer [col int-map]
             :as dsp]
            [tech.v2.datatype.functional :as dfn]
            [tech.ml.dataset.pipeline.column-filters :as cf]
            [tech.ml.dataset :as dataset]
            [tech.ml.dataset.column :as ds-col]
            [tech.ml :as ml]
            [tech.ml.loss :as loss]
            [tech.ml.utils :as ml-utils]

            ;;use tablesaw as dataset backing store
            [tech.libs.tablesaw :as tablesaw]
            [tech.ml.regression :as ml-regression]
            [tech.ml.visualization.vega :as vega-viz]

            ;;put/get nippy
            [tech.io :as io]

            [oz.core :as oz]

            [clojure.pprint :as pp]
            [clojure.set :as c-set]
            [clojure.core.matrix :as m])

  (:import [tech.ml.protocols.etl PETLSingleColumnOperator]
           [java.io File]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(tech.ml.utils/set-slf4j-log-level :warn)


(def src-dataset (tablesaw/path->tablesaw-dataset "data/ames-house-prices/train.csv"))


(def filtered-ds (dsp/filter src-dataset "GrLivArea" #(dfn/< (dsp/col) 4000)))


(defn initial-pipeline-from-article
  [dataset]
  (-> dataset
      ;;Convert any numeric or boolean columns to be all of one datatype.
      (dsp/->datatype #(cf/or % cf/numeric? cf/boolean?))
      (dsp/remove-columns ["Id"])
      (dsp/m= "SalePrice" #(dfn/log1p (dsp/col)))))


(defn more-categorical
  [dataset]
  (dsp/assoc-metadata dataset ["MSSubClass" "OverallQual" "OverallCond"] :categorical? true))

(println "pre-categorical-count" (count (cf/categorical? filtered-ds)))

(def post-categorical-fix (-> filtered-ds
                              initial-pipeline-from-article
                              more-categorical))

(println "post-categorical-count" (count (cf/categorical? post-categorical-fix)))


;; Impressive patience to come up with this list!!
(defn initial-missing-entries
  [dataset]
  (-> dataset
      ;; Handle missing values for features where median/mean or most common value doesn't
      ;; make sense

      ;; Alley : data description says NA means "no alley access"
      (dsp/replace-missing "Alley" "None")
      ;; BedroomAbvGr : NA most likely means 0
      (dsp/replace-missing ["BedroomAbvGr"
                            "BsmtFullBath"
                            "BsmtHalfBath"
                            "BsmtUnfSF"
                            "EnclosedPorch"
                            "Fireplaces"
                            "GarageArea"
                            "GarageCars"
                            "HalfBath"
                            ;; KitchenAbvGr : NA most likely means 0
                            "KitchenAbvGr"
                            "LotFrontage"
                            "MasVnrArea"
                            "MiscVal"
                            ;; OpenPorchSF : NA most likely means no open porch
                            "OpenPorchSF"
                            "PoolArea"
                            ;; ScreenPorch : NA most likely means no screen porch
                            "ScreenPorch"
                            ;; TotRmsAbvGrd : NA most likely means 0
                            "TotRmsAbvGrd"
                            ;; WoodDeckSF : NA most likely means no wood deck
                            "WoodDeckSF"
                            ]
                           0)
      ;; BsmtQual etc : data description says NA for basement features is "no basement"
      (dsp/replace-missing ["BsmtQual"
                            "BsmtCond"
                            "BsmtExposure"
                            "BsmtFinType1"
                            "BsmtFinType2"
                            ;; Fence : data description says NA means "no fence"
                            "Fence"
                            ;; FireplaceQu : data description says NA means "no fireplace"
                            "FireplaceQu"
                            ;; GarageType etc : data description says NA for garage features
                            ;; is "no garage"
                            "GarageType"
                            "GarageFinish"
                            "GarageQual"
                            "GarageCond"
                            ;; MiscFeature : data description says NA means "no misc feature"
                            "MiscFeature"
                            ;; PoolQC : data description says NA means "no pool"
                            "PoolQC"
                            ]
                           "No")
      (dsp/replace-missing "CentralAir" "N")
      (dsp/replace-missing ["Condition1"
                            "Condition2"]
                           "Norm")
      ;; Condition : NA most likely means Normal
      ;; EnclosedPorch : NA most likely means no enclosed porch
      ;; External stuff : NA most likely means average
      (dsp/replace-missing ["ExterCond"
                            "ExterQual"
                            ;; HeatingQC : NA most likely means typical
                            "HeatingQC"
                            ;; KitchenQual : NA most likely means typical
                            "KitchenQual"
                            ]
                           "TA")
      ;; Functional : data description says NA means typical
      (dsp/replace-missing "Functional" "Typ")
      ;; LotShape : NA most likely means regular
      (dsp/replace-missing "LotShape" "Reg")
      ;; MasVnrType : NA most likely means no veneer
      (dsp/replace-missing "MasVnrType" "None")
      ;; PavedDrive : NA most likely means not paved
      (dsp/replace-missing "PavedDrive" "N")
      (dsp/replace-missing "SaleCondition" "Normal")
      (dsp/replace-missing "Utilities" "AllPub")))

(println "pre missing fix #1")
(pp/pprint (dataset/columns-with-missing-seq post-categorical-fix))

(def post-missing (initial-missing-entries post-categorical-fix))

(println "post missing fix #1")

(pp/pprint (dataset/columns-with-missing-seq post-missing))


(def str->number-initial-map
  {
   "Alley"  {"Grvl"  1 "Pave" 2 "None" 0}
   "BsmtCond"  {"No"  0 "Po"  1 "Fa"  2 "TA"  3 "Gd"  4 "Ex"  5}
   "BsmtExposure"  {"No"  0 "Mn"  1 "Av" 2 "Gd"  3}
   "BsmtFinType1"  {"No"  0 "Unf"  1 "LwQ" 2 "Rec"  3 "BLQ"  4
                     "ALQ"  5 "GLQ"  6}
   "BsmtFinType2"  {"No"  0 "Unf"  1 "LwQ" 2 "Rec"  3 "BLQ"  4
                     "ALQ"  5 "GLQ"  6}
   "BsmtQual"  {"No"  0 "Po"  1 "Fa"  2 "TA" 3 "Gd"  4 "Ex"  5}
   "ExterCond"  {"Po"  1 "Fa"  2 "TA" 3 "Gd" 4 "Ex"  5}
   "ExterQual"  {"Po"  1 "Fa"  2 "TA" 3 "Gd" 4 "Ex"  5}
   "FireplaceQu"  {"No"  0 "Po"  1 "Fa"  2 "TA"  3 "Gd"  4 "Ex"  5}
   "Functional"  {"Sal"  1 "Sev"  2 "Maj2"  3 "Maj1"  4 "Mod" 5
                   "Min2"  6 "Min1"  7 "Typ"  8}
   "GarageCond"  {"No"  0 "Po"  1 "Fa"  2 "TA"  3 "Gd"  4 "Ex"  5}
   "GarageQual"  {"No"  0 "Po"  1 "Fa"  2 "TA"  3 "Gd"  4 "Ex"  5}
   "HeatingQC"  {"Po"  1 "Fa"  2 "TA"  3 "Gd"  4 "Ex"  5}
   "KitchenQual"  {"Po"  1 "Fa"  2 "TA"  3 "Gd"  4 "Ex"  5}
   "LandSlope"  {"Sev"  1 "Mod"  2 "Gtl"  3}
   "LotShape"  {"IR3"  1 "IR2"  2 "IR1"  3 "Reg"  4}
   "PavedDrive"  {"N"  0 "P"  1 "Y"  2}
   "PoolQC"  {"No"  0 "Fa"  1 "TA"  2 "Gd"  3 "Ex"  4}
   "Street"  {"Grvl"  1 "Pave"  2}
   "Utilities"  {"ELO"  1 "NoSeWa"  2 "NoSewr"  3 "AllPub"  4}
   })


(defn str->number-pipeline
  [dataset]
  (->> str->number-initial-map
       (reduce (fn [dataset str-num-entry]
                 (apply dsp/string->number dataset str-num-entry))
        dataset)))

(def str-num-dataset (str->number-pipeline post-missing))

(pp/pprint (dataset/dataset-label-map str-num-dataset))


(def replace-maps
  {
   ;; Create new features
   ;; 1* Simplifications of existing features
   ;; The author implicitly leaves values at zero to be zero, so these maps
   ;; are intentionally incomplete
   "SimplOverallQual" {"OverallQual" {1  1, 2  1, 3  1, ;; bad
                                     4  2, 5  2, 6  2, ;; average
                                     7  3, 8  3, 9  3, 10  3 ;; good
                                      }}
   "SimplOverallCond" {"OverallCond" {1  1, 2  1, 3  1,       ;; bad
                                      4  2, 5  2, 6  2,       ;; average
                                      7  3, 8  3, 9  3, 10  3 ;; good
                                      }}
   "SimplPoolQC" {"PoolQC" {1  1, 2  1,    ;; average
                            3  2, 4  2     ;; good
                            }}
   "SimplGarageCond" {"GarageCond" {1  1,             ;; bad
                                    2  1, 3  1,       ;; average
                                    4  2, 5  2        ;; good
                                    }}
   "SimplGarageQual" {"GarageQual" {1  1,             ;; bad
                                    2  1, 3  1,       ;; average
                                    4  2, 5  2        ;; good
                                    }}
   "SimplFireplaceQu"  {"FireplaceQu" {1  1,           ;; bad
                                        2  1, 3  1,     ;; average
                                        4  2, 5  2      ;; good
                                        }}
   "SimplFunctional"  {"Functional" {1  1, 2  1,           ;; bad
                                      3  2, 4  2,           ;; major
                                      5  3, 6  3, 7  3,     ;; minor
                                      8  4                  ;; typical
                                      }}
   "SimplKitchenQual" {"KitchenQual" {1  1,             ;; bad
                                      2  1, 3  1,       ;; average
                                      4  2, 5  2        ;; good
                                      }}
   "SimplHeatingQC"  {"HeatingQC" {1  1,           ;; bad
                                   2  1, 3  1,     ;; average
                                   4  2, 5  2      ;; good
                                    }}
   "SimplBsmtFinType1"  {"BsmtFinType1" {1  1,         ;; unfinished
                                         2  1, 3  1,   ;; rec room
                                         4  2, 5  2, 6  2 ;; living quarters
                                          }}
   "SimplBsmtFinType2" {"BsmtFinType2" {1 1,           ;; unfinished
                                        2 1, 3 1,      ;; rec room
                                        4 2, 5 2, 6 2  ;; living quarters
                                        }}
   "SimplBsmtCond" {"BsmtCond" {1 1,    ;; bad
                                2 1, 3 1, ;; average
                                4 2, 5 2  ;; good
                                }}
   "SimplBsmtQual" {"BsmtQual" {1 1,      ;; bad
                                2 1, 3 1, ;; average
                                4 2, 5 2  ;; good
                                }}
   "SimplExterCond" {"ExterCond" {1 1,      ;; bad
                                  2 1, 3 1, ;; average
                                  4 2, 5 2  ;; good
                                  }}
   "SimplExterQual" {"ExterQual" {1 1,      ;; bad
                                  2 1, 3 1, ;; average
                                  4 2, 5 2  ;; good
                                  }}})


(defn simplifications
  [dataset]
  (->> replace-maps
       (reduce (fn [dataset [target-name coldata-map]]
                 (let [[col-name replace-data] (first coldata-map)]
                   (dsp/m= dataset target-name
                           #(dsp/int-map replace-data (dsp/col col-name)
                                         :not-strict? true))))
               dataset)))

(def replace-dataset (simplifications str-num-dataset))

(pp/pprint (-> (dataset/column str-num-dataset "KitchenQual")
                (ds-col/unique)))

(pp/pprint (-> (dataset/column replace-dataset "SimplKitchenQual")
               (ds-col/unique)))


(defn linear-combinations
  [dataset]
  (-> dataset
      (dsp/m= "OverallGrade" #(dfn/* (col "OverallQual") (col "OverallCond")))
      ;; Overall quality of the garage
      (dsp/m= "GarageGrade" #(dfn/* (col "GarageQual") (col "GarageCond")))
      ;; Overall quality of the exterior
      (dsp/m= "ExterGrade"#(dfn/* (col "ExterQual") (col "ExterCond")))
      ;; Overall kitchen score
      (dsp/m= "KitchenScore" #(dfn/* (col "KitchenAbvGr") (col "KitchenQual")))
      ;; Overall fireplace score
      (dsp/m= "FireplaceScore" #(dfn/* (col "Fireplaces") (col "FireplaceQu")))
      ;; Overall garage score
      (dsp/m= "GarageScore" #(dfn/* (col "GarageArea") (col "GarageQual")))
      ;; Overall pool score
      (dsp/m= "PoolScore" #(dfn/* (col "PoolArea") (col "PoolQC")))
      ;; Simplified overall quality of the house
      (dsp/m= "SimplOverallGrade" #(dfn/* (col "SimplOverallQual") (col "SimplOverallCond")))
      ;; Simplified overall quality of the exterior
      (dsp/m= "SimplExterGrade" #(dfn/* (col "SimplExterQual") (col "SimplExterCond")))
      ;; Simplified overall pool score
      (dsp/m= "SimplPoolScore" #(dfn/* (col "PoolArea") (col "SimplPoolQC")))
      ;; Simplified overall garage score
      (dsp/m= "SimplGarageScore" #(dfn/* (col "GarageArea") (col "SimplGarageQual")))
      ;; Simplified overall fireplace score
      (dsp/m= "SimplFireplaceScore" #(dfn/* (col "Fireplaces") (col "SimplFireplaceQu")))
      ;; Simplified overall kitchen score
      (dsp/m= "SimplKitchenScore" #(dfn/* (col "KitchenAbvGr" ) (col "SimplKitchenQual")))
      ;; Total number of bathrooms
      (dsp/m= "TotalBath" #(dfn/+ (col "BsmtFullBath") (dfn/* 0.5 (col "BsmtHalfBath"))
                                  (col "FullBath") (dfn/* 0.5 (col "HalfBath"))))
      ;; Total SF for house (incl. basement)
      (dsp/m= "AllSF"  #(dfn/+ (col "GrLivArea") (col "TotalBsmtSF")))
      ;; Total SF for 1st + 2nd floors
      (dsp/m= "AllFlrsSF" #(dfn/+ (col "1stFlrSF") (col "2ndFlrSF")))
      ;; Total SF for porch
      (dsp/m= "AllPorchSF" #(dfn/+ (col "OpenPorchSF") (col "EnclosedPorch")
                                   (col "3SsnPorch") (col "ScreenPorch")))
      ;; Encode MasVrnType
      (dsp/string->number "MasVnrType" ["None" "BrkCmn" "BrkFace" "CBlock" "Stone"])
      (dsp/m= "HasMasVnr" #(dfn/not-eq (col "MasVnrType") 0))))


(def linear-combined-ds (linear-combinations replace-dataset))



(let [print-columns ["TotalBath" "BsmtFullBath" "BsmtHalfBath"
                     "FullBath" "HalfBath"]]
  (println (dataset/select linear-combined-ds print-columns (range 10))))

(let [print-columns ["AllSF" "GrLivArea" "TotalBsmtSF"]]
  (println (dataset/select linear-combined-ds print-columns (range 10))))


(def article-correlations
  ;;Default for pandas is pearson.
  ;;  Find most important features relative to target
  (->>
   {"SalePrice"            1.000
    "OverallQual"          0.819
    "AllSF"                0.817
    "AllFlrsSF"            0.729
    "GrLivArea"            0.719
    "SimplOverallQual"     0.708
    "ExterQual"            0.681
    "GarageCars"           0.680
    "TotalBath"            0.673
    "KitchenQual"          0.667
    "GarageScore"          0.657
    "GarageArea"           0.655
    "TotalBsmtSF"          0.642
    "SimplExterQual"       0.636
    "SimplGarageScore"     0.631
    "BsmtQual"             0.615
    "1stFlrSF"             0.614
    "SimplKitchenQual"     0.610
    "OverallGrade"         0.604
    "SimplBsmtQual"        0.594
    "FullBath"             0.591
    "YearBuilt"            0.589
    "ExterGrade"           0.587
    "YearRemodAdd"         0.569
    "FireplaceQu"          0.547
    "GarageYrBlt"          0.544
    "TotRmsAbvGrd"         0.533
    "SimplOverallGrade"    0.527
    "SimplKitchenScore"    0.523
    "FireplaceScore"       0.518
    "SimplBsmtCond"        0.204
    "BedroomAbvGr"         0.204
    "AllPorchSF"           0.199
    "LotFrontage"          0.174
    "SimplFunctional"      0.137
    "Functional"           0.136
    "ScreenPorch"          0.124
    "SimplBsmtFinType2"    0.105
    "Street"               0.058
    "3SsnPorch"            0.056
    "ExterCond"            0.051
    "PoolArea"             0.041
    "SimplPoolScore"       0.040
    "SimplPoolQC"          0.040
    "PoolScore"            0.040
    "PoolQC"               0.038
    "BsmtFinType2"         0.016
    "Utilities"            0.013
    "BsmtFinSF2"           0.006
    "BsmtHalfBath"        -0.015
    "MiscVal"             -0.020
    "SimplOverallCond"    -0.028
    "YrSold"              -0.034
    "OverallCond"         -0.037
    "LowQualFinSF"        -0.038
    "LandSlope"           -0.040
    "SimplExterCond"      -0.042
    "KitchenAbvGr"        -0.148
    "EnclosedPorch"       -0.149
    "LotShape"            -0.286
    }
   (sort-by second >)))


(def tech-ml-correlations (get (dataset/correlation-table
                                linear-combined-ds
                                :pearson)
                               "SalePrice"))

(pp/print-table (map #(zipmap [:pandas :tech.ml.dataset]
                              [%1 %2])
                     (take 20 article-correlations)
                     (take 20 tech-ml-correlations)))



(defn polynomial-combinations
  [dataset]
  (let [correlation-colnames (->> tech-ml-correlations
                                  (drop 1)
                                  (take 10)
                                  (map first))]
    (->> correlation-colnames
         (reduce (fn [dataset colname]
                   (-> dataset
                       (dsp/m= (str colname "-s2") #(dfn/pow (col colname) 2))
                       (dsp/m= (str colname "-s3") #(dfn/pow (col colname) 3))
                       (dsp/m= (str colname "-sqrt") #(dfn/sqrt (col colname)))))
                 dataset))))

(def poly-data (polynomial-combinations linear-combined-ds))


(println (dataset/select poly-data
                         ["OverallQual"
                          "OverallQual-s2"
                          "OverallQual-s3"
                          "OverallQual-sqrt"]
                         (range 10)))

(def target-column-name "SalePrice")

(defn numeric-features-column-filter
  [dataset]
  (cf/and dataset
          (cf/not dataset target-column-name)
          (cf/not dataset cf/target?)
          cf/numeric?))


(defn categorical-features-column-filter
  [dataset]
  (cf/and dataset
          (cf/not dataset target-column-name)
          (cf/not dataset cf/target?)
          (cf/not dataset cf/numeric?)))

(def numerical-features (numeric-features-column-filter poly-data))
(def categorical-features (categorical-features-column-filter poly-data))


(println (count numerical-features))

(println (count categorical-features))

;;I printed out the categorical features from the when using pandas.
(pp/pprint (->> (c-set/difference
                 (set ["MSSubClass", "MSZoning", "Alley", "LandContour", "LotConfig",
                       "Neighborhood", "Condition1", "Condition2", "BldgType",
                       "HouseStyle", "RoofStyle", "RoofMatl", "Exterior1st",
                       "Exterior2nd", "MasVnrType", "Foundation", "Heating", "CentralAir",
                       "Electrical", "GarageType", "GarageFinish", "Fence", "MiscFeature",
                       "MoSold", "SaleType", "SaleCondition"])
                  (set categorical-features))
                 (map (comp ds-col/metadata (partial dataset/column poly-data)))))


(defn skew-column-filter
  [dataset]
  (cf/and dataset
          cf/numeric?
          ))
(defn pp-str
  [ds]
  (with-out-str
    (pp/pprint ds)))


(defn print-table-str
  ([ks data]
   (with-out-str
     (->> data
          (map (fn [item-map]
                 (->> item-map
                      (map (fn [[k v]]
                             [k (if (or (float? v)
                                        (double? v))
                                  (format "%.3f" v)
                                  v)]))
                      (into {}))))
          (pp/print-table ks))))
  ([data]
   (print-table-str (sort (keys (first data))) data)))


(defn render-results
  [title gridsearch-results]
  [:div
   [:h3 title]
   (vega-viz/accuracy-graph gridsearch-results :y-scale [0.10, 0.20])])


(defn train-regressors
  [dataset-name dataset loss-fn opts]
  (let [dataset (dataset/from-prototype dataset dataset-name (dataset/columns dataset))
        base-gridsearch-systems [:smile.regression/lasso
                                 :xgboost/regression]
        trained-results (ml-regression/train-regressors
                         dataset opts
                         :loss-fn loss-fn
                         :gridsearch-regression-systems base-gridsearch-systems)]
    (println "Got" (count trained-results) "TRained results")
    (println "Should have got 4 results")
    (vec trained-results)))


(defn train-graph-regressors
  [dataset-name dataset loss-fn opts]
  (let [fname (format "file://trained-models-%s.nippy" dataset-name)
        trained-results
        (if (not (.exists ^File (io/file fname)))
          (let [trained-results (train-regressors dataset-name dataset loss-fn opts)]
            (io/put-nippy! fname trained-results)
            trained-results)
          (io/get-nippy fname))]
    (->> (apply concat [(render-results dataset-name trained-results)]
                (->> trained-results
                     (sort-by :average-loss)
                     (map (fn [model-result]
                            [[:div
                              [:h3 (format "%s-%.4f"
                                           (get-in model-result [:options :model-type])
                                           (:average-loss model-result))]
                              [:div
                               [:span
                                [:h4 "Predictions"]
                                (vega-viz/graph-regression-verification-results
                                 model-result :target-key :predictions
                                 :y-scale [10 14]
                                 :x-scale [10 14])]
                               [:span
                                [:h4 "Residuals"]
                                (vega-viz/graph-regression-verification-results
                                 model-result :target-key :residuals
                                 :y-scale [10 14]
                                 :x-scale [-1 1])]]]]))))
         (into [:div]))))
