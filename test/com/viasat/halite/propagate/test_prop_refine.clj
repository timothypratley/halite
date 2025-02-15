;; Copyright (c) 2022 Viasat, Inc.
;; Licensed under the MIT license

(ns com.viasat.halite.propagate.test-prop-refine
  (:require [clojure.test :as t :refer [deftest is are testing]]
            [com.viasat.halite.transpile.ssa :as ssa]
            [com.viasat.halite.transpile.rewriting :as rewriting]
            [com.viasat.halite.propagate.prop-composition :as pc]
            [com.viasat.halite.propagate.prop-refine :as pr]
            [schema.core :as s]
            [com.viasat.halite :as halite]))

(def lower-spec-bound #'pc/lower-spec-bound)

(deftest test-internals
  (is (= {:$type :spec/A,
          :an 5,
          :>spec$B {:$type :spec/B,
                    :>spec$C {:$type :spec/C,
                              :>spec$D {:$type :spec/D,
                                        :dn 7}}}}
         (pr/assoc-in-refn-path {:$type :spec/A, :an 5}
                                [:spac/A :spec/B :spec/C :spec/D]
                                {:$type :spec/D, :dn 7}))))

(deftest test-basics
  (let [sctx (ssa/spec-map-to-ssa
              '{:my/A {:fields {:a1 :Integer}
                       :constraints [["a1_pos" (< 0 a1)]]}
                :my/B {:fields {:b1 :Integer}
                       :refines-to {:my/A {:expr {:$type :my/A, :a1 (+ 10 b1)}}}}
                :my/C {:fields {:c1 :Integer}
                       :refines-to {:my/B {:expr {:$type :my/B, :b1 (+ 5 c1)}}}}
                :my/D {:refines-to {:my/A {:expr {:$type :my/A, :a1 10}}}}})]

    (s/with-fn-validation
      (is (= {:$type :my/C,
              :c1 {:$in [-14 1000]},
              :>my$B {:$type :my/B,
                      :b1 {:$in [-9 9]},
                      :>my$A {:$type :my/A,
                              :a1 {:$in [1 19]}}}}

             (pr/lower-bound
              sctx
              (pr/make-rgraph sctx)
              {:$type :my/C,
               :c1 {:$in [-14 1000]},
               :$refines-to {:my/B {:b1 {:$in [-9 9]}}
                             :my/A {:a1 {:$in [1 19]}}}})))

      (is (= {:$type :my/C,
              :c1 {:$in [-14 1000]},
              :$refines-to {:my/B {:b1 {:$in [-9 9]}}
                            :my/A {:a1 {:$in [1 19]}}}}

             (pr/raise-bound
              sctx
              {:$type :my/C,
               :c1 {:$in [-14 1000]},
               :>my$B {:$type :my/B,
                       :b1 {:$in [-9 9]},
                       :>my$A {:$type :my/A,
                               :a1 {:$in [1 19]}}}})))

      (is (thrown-with-msg? Exception #"No.*refinement path"
                            (pr/lower-bound
                             sctx
                             (pr/make-rgraph sctx)
                             {:$type :my/C,
                              :$refines-to {:my/D {}}}))))

    (is (= {:$type :my/C,
            :c1 {:$in [-14 985]},
            :$refines-to {:my/B {:b1 {:$in [-9 990]}}
                          :my/A {:a1 {:$in [1 1000]}}}}
           (pr/propagate sctx {:$type :my/C})))))

(deftest test-basic-refine-to
  (let [sctx (ssa/spec-map-to-ssa
              '{:my/A {:fields {:a1 :Integer}
                       :constraints [["a1_pos" (< 0 a1)]]}
                :my/B {:fields {:b1 :Integer}
                       :refines-to {:my/A {:expr {:$type :my/A, :a1 (+ 10 b1)}}}}
                :my/C {:fields {:c1 :Integer}
                       :refines-to {:my/B {:expr {:$type :my/B, :b1 (+ 5 c1)}}}}
                :my/D {:fields {:a [:Instance :my/A]
                                :c1 :Integer}
                       :constraints [["rto" (= a (refine-to {:$type :my/C
                                                             :c1 c1}
                                                            :my/A))]]}})]
    (is (= '{:my/A {:fields {:a1 :Integer},
                    :constraints [["$all" (< 0 a1)]],
                    :refines-to {}},
             :my/B {:fields {:b1 :Integer, :>my$A [:Instance :my/A]},
                    :constraints [["$all" (= >my$A {:a1 (+ 10 b1),
                                                    :$type :my/A})]],
                    :refines-to {}},
             :my/C {:fields {:c1 :Integer, :>my$B [:Instance :my/B]},
                    :constraints [["$all" (let [v1 (+ 5 c1)]
                                            (= >my$B
                                               {:>my$A {:a1 (+ 10 v1),
                                                        :$type :my/A},
                                                :b1 v1,
                                                :$type :my/B}))]],
                    :refines-to {}},
             :my/D {:fields {:a [:Instance :my/A], :c1 :Integer},
                    :constraints [["$all" (let [v1 (+ 5 c1)
                                                v2 (get {:$type :my/C
                                                         :c1 c1
                                                         :>my$B {:>my$A {:$type :my/A
                                                                         :a1 (+ 10 v1)}
                                                                 :b1 v1,
                                                                 :$type :my/B}}
                                                        :>my$B)
                                                v3 (get v2 :>my$A)]
                                            (= a v3))]],
                    :refines-to {}}}
           (update-vals (pr/lower-spec-refinements sctx (pr/make-rgraph sctx))
                        ssa/spec-from-ssa)))

    (is (= {:$type :my/D,
            :c1 {:$in [-14 985]}
            :a {:$type :my/A,
                :a1 {:$in [1 1000]}}}
           (pr/propagate sctx {:$type :my/D})))))

(deftest test-propagate-and-refinement
  (let [specs (ssa/spec-map-to-ssa
               '{:ws/A
                 {:fields {:an :Integer}
                  :constraints [["a1" (<= an 6)]]
                  :refines-to {:ws/B {:expr {:$type :ws/B :bn (+ 1 an)}}}}

                 :ws/B
                 {:fields {:bn :Integer}
                  :constraints [["b1" (< 0 bn)]]
                  :refines-to {:ws/C {:expr {:$type :ws/C :cn bn}}}}

                 :ws/C
                 {:fields {:cn :Integer}
                  :constraints [["c1" (= 0 (mod cn 2))]]}

                 :ws/D
                 {:fields {:a [:Instance :ws/A] :dm :Integer, :dn :Integer}
                  :constraints [["d1" (= dm (get (refine-to {:$type :ws/A :an dn} :ws/C) :cn))
                                 "d2" (= (+ 1 dn) (get (refine-to a :ws/B) :bn))]]}})]

    (are [in out]
         (= out (pr/propagate specs in))

      {:$type :ws/C :cn {:$in (set (range 10))}} {:$type :ws/C :cn {:$in #{0 2 4 6 8}}}

      {:$type :ws/B :bn {:$in (set (range 10))}} {:$type :ws/B
                                                  :bn {:$in #{2 4 6 8}}
                                                  :$refines-to {:ws/C {:cn {:$in [2 8]}}}}

      {:$type :ws/A :an {:$in (set (range 10))}} {:$type :ws/A
                                                  :an {:$in #{1 3 5}}
                                                  :$refines-to {:ws/B {:bn {:$in [2 6]}}
                                                                :ws/C {:cn {:$in [2 6]}}}}

      {:$type :ws/D} {:$type :ws/D,
                      :a {:$type :ws/A,
                          :an {:$in [1 5]}
                          :$refines-to {:ws/B {:bn {:$in [2 6]}}
                                        :ws/C {:cn {:$in [2 6]}}}},
                      :dm {:$in [2 6]},
                      :dn {:$in [1 5]}})))

(deftest test-refine-to-of-unknown-type
  (let [sctx (ssa/spec-map-to-ssa
              '{:t/A {:fields {:y :Integer}
                      :refines-to {:t/C {:expr {:$type :t/C
                                                :cn (+ y 10)}}}}
                :t/B {:fields {:y :Integer}
                      :refines-to {:t/C {:expr {:$type :t/C
                                                :cn (+ y 20)}}}}
                :t/C {:fields {:cn :Integer}}
                :t/D {:fields {:x :Integer
                               :cn :Integer}
                      :constraints [["the point"
                                     (= cn
                                        (get (refine-to (if (< x 5)
                                                          {:$type :t/A, :y x}
                                                          {:$type :t/B, :y x})
                                                        :t/C)
                                             :cn))]]}})]
    (is (= {:$type :t/D, :x 4, :cn 14}
           (pr/propagate sctx {:$type :t/D, :x 4})))

    (is (= {:$type :t/D, :x 6, :cn 26}
           (pr/propagate sctx {:$type :t/D, :x 6})))))

#_(deftest test-optionals-travel-together
    (let [specs '{:ws/A {:fields {:an "Integer"}
                         :constraints [["a1" (< 0 an)]]
                         :refines-to {}}
                  :ws/B {:fields {:bn "Integer"}
                         :constraints [["b1" (< bn 10)]]
                         :refines-to {:ws/A {:expr {:$type :ws/A :an bn}}}}
                  :ws/C {:fields {:b [:Maybe :ws/B] :cn "Integer"}
                         :constraints [["c1" (if-value b (= cn (get (refine-to b :ws/A) :an)) true)]]
                         :refines-to {}}}
          sctx (-> specs (update-vals full-spec-info) ssa/spec-map-to-ssa)]

      (is (=
           {:$type :ws/C,
            :b {:$type [:Maybe :ws/B],
                :bn {:$in [-1000 1000]},
                :$refines-to {:ws/A :Unset}}, ;; TODO: since this is unset, :b should also be unset
            :cn {:$in [-1000 1000]}}

           (pr/propagate
            sctx
            {:$type :ws/C
             :b {:$type [:Maybe :ws/B]
                 :$refines-to {:ws/A {:an 12}}}})))))

(deftest test-lower-bound
  (s/with-fn-validation
    (let [sctx (ssa/spec-map-to-ssa
                '{:ws/A {:fields {:an :Integer}
                         :constraints [["a1" (< 0 an)]]
                         :refines-to {}}
                  :ws/B {:fields {:bn :Integer}
                         :constraints [["b1" (< bn 10)]]
                         :refines-to {:ws/A {:expr {:$type :ws/A :an bn}}}}
                  :ws/C {:fields {:b [:Maybe [:Instance :ws/B]]
                                  :cn :Integer}
                         :constraints [["c1" (if-value b (= cn (get (refine-to b :ws/A) :an)) true)]]
                         :refines-to {}}})]

      (are [in out]
           (= out (pr/lower-bound sctx (pr/make-rgraph sctx) in))

        {:$type :ws/C :b {:$type [:Maybe :ws/B] :$refines-to {:ws/A {:an 12}}}}
        {:$type :ws/C :b {:$type [:Maybe :ws/B] :>ws$A {:$type :ws/A, :an 12}}}

        {:$type :ws/C :b {:$type [:Maybe :ws/B] :$refines-to {:ws/A {:an {:$in [10 12]}}}}}
        {:$type :ws/C :b {:$type [:Maybe :ws/B] :>ws$A {:$type :ws/A :an {:$in [10 12]}}}}

        {:$type :ws/C :b {:$type [:Maybe :ws/B] :$refines-to {:ws/A {:an {:$in #{10 11 12}}}}}}
        {:$type :ws/C :b {:$type [:Maybe :ws/B] :>ws$A {:$type :ws/A :an {:$in #{10 11 12}}}}}))))

(def nested-optionals-spec-env
  '{:ws/A {:fields {:b1 [:Maybe [:Instance :ws/B]], :b2 [:Maybe [:Instance :ws/B]], :ap :Boolean}
           :constraints [["a1" (= b1 b2)]
                         ["a2" (=> ap (if-value b1 true false))]]}
    :ws/B {:fields {:bx :Integer, :bw [:Maybe :Integer], :bp :Boolean
                    :c1 [:Instance :ws/C]
                    :c2 [:Maybe [:Instance :ws/C]]}
           :constraints [["b1" (= bw (when bp bx))]
                         ["b2" (< bx 15)]]}
    :ws/C {:fields {:cx :Integer
                    :cw [:Maybe :Integer]}}
    :ws/D {:fields {:dx :Integer}
           :refines-to {:ws/B {:expr {:$type :ws/B
                                      :bx (+ dx 1)
                                      :bw (when (= 0 (mod (abs dx) 2))
                                            (div dx 2))
                                      :bp false
                                      :c1 {:$type :ws/C :cx dx :cw dx}
                                      :c2 {:$type :ws/C :cx dx :cw 8}}}}}})

(deftest test-propagate-for-spec-valued-optionals
  (let [opts {:default-int-bounds [-10 10]}
        specs (ssa/spec-map-to-ssa nested-optionals-spec-env)]

    (are [in out]
         (= out (pr/propagate specs opts in))

      {:$type :ws/D}
      {:$type :ws/D
       :dx {:$in [-9 9]}
       :$refines-to {:ws/B {:bp false,
                            :bw :Unset,
                            :bx {:$in [-8 10]},
                            :c1 {:$type :ws/C, :cw {:$in [-9 9]}, :cx {:$in [-9 9]}},
                            :c2 {:$type :ws/C, :cw 8, :cx {:$in [-9 9]}}}}}

      {:$type :ws/D :dx {:$in (set (range -5 6))}}
      {:$type :ws/D
       :dx {:$in #{-5 -3 -1 1 3 5}}
       :$refines-to {:ws/B {:bp false,
                            :bw :Unset,
                            :bx {:$in [-4 6]},
                            :c1 {:$type :ws/C, :cw {:$in [-5 5]}, :cx {:$in [-5 5]}},
                            :c2 {:$type :ws/C, :cw 8, :cx {:$in [-5 5]}}}}})))

(deftest test-refine-optional
  ;; The 'features' that interact here: valid? and instance literals w/ unassigned variables.
  (let [sctx (ssa/spec-map-to-ssa
              '{:my/A {:abstract? true
                       :fields {:a1 [:Maybe :Integer]
                                :a2 [:Maybe :Integer]}
                       :constraints [["a1_pos" (if-value a1 (> a1 0) true)]
                                     ["a2_pos" (if-value a2 (> a2 0) true)]]}
                :my/B {:abstract? false
                       :fields {:b :Integer}
                       :refines-to {:my/A {:expr {:$type :my/A, :a1 b}}}}})]

    (is (= {:$type :my/B
            :b {:$in [1 100]}
            :$refines-to {:my/A {:a1 {:$in [1 100]}, :a2 :Unset}}}
           (pr/propagate sctx {:$type :my/B :b {:$in [-100 100]}})))))

(deftest test-basic-refines-to-bounds
  (let [specs (ssa/spec-map-to-ssa
               '{:my/A {:fields {:a1 :Integer}
                        :constraints [["a1_pos" (< 0 a1)]]}
                 :my/B {:fields {:b1 :Integer}
                        :refines-to {:my/A {:expr {:$type :my/A, :a1 (+ 10 b1)}}}}
                 :my/C {:fields {:cb [:Instance :my/B]}}})]

    (testing "Refinement bounds can be given and will influence resulting bound"
      (is (= {:$type :my/C,
              :cb {:$type :my/B,
                   :b1 -5
                   :$refines-to {:my/A {:a1 5}}}}
             (pr/propagate specs {:$type :my/C,
                                  :cb {:$type :my/B
                                       :$refines-to {:my/A {:a1 5}}}}))))

    (testing "Refinement bounds are generated even when not given."
      (is (= {:$type :my/C,
              :cb {:$type :my/B
                   :b1 {:$in [-9 990]}
                   :$refines-to {:my/A {:a1 {:$in [1 1000]}}}}}
             (pr/propagate specs {:$type :my/C}))))

    (testing "Refinement bounds at top level of composition"
      (is (= {:$type :my/B
              :b1 -7
              :$refines-to {:my/A {:a1 3}}}
             (pr/propagate specs {:$type :my/B
                                  :$refines-to {:my/A {:a1 3}}}))))))

(deftest test-refines-to-bounds-with-optionals
  (let [specs '{:my/A {:fields {:a1 :Integer, :a2 [:Maybe :Integer]}
                       :constraints [["a1_pos" (< 0 a1)]]}
                :my/B {:fields {:b1 :Integer}
                       :refines-to {:my/A {:expr {:$type :my/A,
                                                  :a1 (+ 10 b1),
                                                  :a2 (when (< 5 b1) (+ 2 b1))}}}}
                :my/C {:fields {:cb [:Maybe [:Instance :my/B]]}}}
        sctx (ssa/spec-map-to-ssa specs)]

    (testing "basic optionals"
      (is (= {:$type :my/B
              :b1 {:$in [-9 990]}
              :$refines-to {:my/A {:a1 {:$in [1 1000]}
                                   :a2 {:$in [8 992 :Unset]}}}}
             (pr/propagate sctx {:$type :my/B})))

      (is (= {:$type :my/B,
              :b1 -5
              :$refines-to {:my/A {:a1 5, :a2 :Unset}}}
             (pr/propagate sctx {:$type :my/B
                                 :$refines-to {:my/A {:a1 5}}})))

      (is (= {:$type :my/B,
              :b1 {:$in [-9 5]}
              :$refines-to {:my/A {:a1 {:$in [1 15]},
                                   :a2 :Unset}}}
             (pr/propagate sctx {:$type :my/B
                                 :$refines-to {:my/A {:a2 :Unset}}})))

      (is (= {:$type :my/C,
              :cb {:$type [:Maybe :my/B],
                   :b1 {:$in [-9 990]},
                   :$refines-to {:my/A {:a1 {:$in [1 1000]},
                                        :a2 {:$in [-1000 1000 :Unset]}}}}}
             (pr/propagate sctx {:$type :my/C}))))

    (testing "transitive refinement bounds"
      (let [sctx (ssa/spec-map-to-ssa
                  (merge specs
                         '{:my/D {:fields {:d1 :Integer}
                                  :refines-to {:my/B {:expr {:$type :my/B,
                                                             :b1 (* 2 d1)}}}}}))]

        (is (= {:$type :my/D,
                :d1 {:$in [-4 495]},
                :$refines-to {:my/B {:b1 {:$in [-8 990]}},
                              :my/A {:a1 {:$in [2 1000]},
                                     :a2 {:$in [8 992 :Unset]}}}}
               (pr/propagate sctx {:$type :my/D})))

        (is (= {:$type :my/D,
                :d1 {:$in [3 6]},
                :$refines-to {:my/B {:b1 {:$in [6 12]}},
                              :my/A {:a1 {:$in [16 22]},
                                     :a2 {:$in [8 14]}}}}
               (pr/propagate sctx {:$type :my/D,
                                   :$refines-to {:my/A {:a2 {:$in [-5 15]}}}})))

        #_;; old version produced these tighter bounds:
          {:$type :my/D,
           :d1 {:$in [3 6]},
           :$refines-to {:my/B {:b1 {:$in [6 12]}},
                         :my/A {:a1 {:$in [16 22]},
                                :a2 {:$in [8 14]}}}}
        (is (= {:$type :my/D,
                :d1 {:$in [-2 7]},
                :$refines-to {:my/B {:b1 {:$in [-4 14]}},
                              :my/A {:a1 {:$in [6 24]},
                                     :a2 {:$in [8 16 :Unset]}}}}
               (pr/propagate sctx {:$type :my/D,
                                   :$refines-to {:my/A {:a2 {:$in [-5 15]}}
                                                 :my/B {:b1 {:$in [-5 15]}}}})))))

    (testing "nested refinement bounds"
      (let [sctx (ssa/spec-map-to-ssa
                  (merge specs
                         '{:my/D {:fields {:d1 :Integer}
                                  :refines-to {:my/C {:expr {:$type :my/C,
                                                             :cb {:$type :my/B
                                                                  :b1 (* d1 2)}}}}}}))]

        (is (= {:$type :my/D,
                :d1 {:$in [-4 495]},
                :$refines-to {:my/C {:cb {:$type :my/B,
                                          :b1 {:$in [-8 990]},
                                          :$refines-to {:my/A {:a1 {:$in [2 1000]},
                                                               :a2 {:$in [8 992 :Unset]}}}}}}}
               (pr/propagate sctx {:$type :my/D})))

        (is (= {:$type :my/D,
                :d1 3,
                :$refines-to {:my/C {:cb {:$type :my/B,
                                          :b1 6,
                                          :$refines-to {:my/A {:a1 16,
                                                               :a2 8}}}}}}
               (pr/propagate sctx {:$type :my/D,
                                   :$refines-to {:my/C {:cb {:$type :my/B,
                                                             :$refines-to {:my/A {:a2 {:$in [-9 9]}}}}}}})))))))

(deftest test-maybe-refines-to-bounds-tight
  (let [specs '{:my/A {:fields {:ab [:Maybe [:Instance :my/B]]}}
                :my/B {:refines-to {:my/C {:expr {:$type :my/C
                                                  :cn 5}}}}
                :my/C {:fields {:cn :Integer}}}
        sctx (ssa/spec-map-to-ssa specs)]

    (is (= '{:fields {:ab? :Boolean, :ab|>my$C|cn [:Maybe :Integer]}
             :constraints [["vars"
                            (valid?
                             {:$type :my/A,
                              :ab (when ab?
                                    (if-value ab|>my$C|cn
                                              {:$type :my/B,
                                               :>my$C {:$type :my/C, :cn ab|>my$C|cn}}
                                              $no-value))})]
                           ["$ab?" (= ab? (if-value ab|>my$C|cn true false))]]}
           (pc/spec-ify-bound (pr/lower-spec-refinements sctx (pr/make-rgraph sctx))
                              {:$type :my/A})))

    (is (= {:$type :my/A,
            :ab {:$type [:Maybe :my/B], :$refines-to #:my{:C {:cn 5}}}}
           (pr/propagate sctx {:$type :my/A})))))

(deftest test-optionals
  (rewriting/with-captured-traces
    (let [specs '{:my/A {:fields {:a1 :Integer, :a2 [:Maybe :Integer]}
                         :constraints [["a1_pos" (< 0 a1)]]}
                  :my/B {:fields {:b1 :Integer}
                         :refines-to {:my/A {:expr {:$type :my/A,
                                                    :a1 (+ 10 b1),
                                                    :a2 (when (< 5 b1) (+ 2 b1))}}}}
                  :my/C {:fields {:cb [:Maybe [:Instance :my/B]]}}}
          sctx (ssa/spec-map-to-ssa specs)]

      (is (= {:$type :my/C,
              :cb {:$type [:Maybe :my/B],
                   :b1 {:$in [-9 990]},
                   :$refines-to {:my/A {:a1 {:$in [1 1000]},
                                        :a2 {:$in [-1000 1000 :Unset]}}}}}
             (pr/propagate sctx {:$type :my/C})))

      ;; There was once a version of propagate that produced these bounds, which
      ;; also seem valid:
      #_{:$type :my/C,
         :cb {:$type [:Maybe :my/B],
              :b1 {:$in [-1000 1000]},
              :$refines-to {:my/A {:a1 {:$in [1 1000]},
                                   :a2 {:$in [8 992 :Unset]}}}}}
      #_(prn :eval
             (halite/eval-expr specs (halite/type-env {}) (halite/env {})
                               '(refine-to {:$type :my/B :b1 991} :my/A))))))

(deftest test-refines-to-bounds-errors
  (let [sctx (ssa/spec-map-to-ssa
              '{:my/A {:fields {:a1 :Integer}
                       :constraints [["a1_pos" (< 0 a1)]]}
                :my/B {:fields {:b1 :Integer}
                       :refines-to {:my/A {:expr {:$type :my/A, :a1 (+ 10 b1)}}}}
                :my/C {:fields {:c1 :Integer}
                       :refines-to {:my/B {:expr {:$type :my/B, :b1 (+ 5 c1)}}}}})]

    (testing "transitive refinements can be listed directly"
      (is (= {:$type :my/C,
              :c1 -10,
              :$refines-to {:my/B {:b1 -5},
                            :my/A {:a1 5}}}
             (pr/propagate sctx {:$type :my/C
                                 :$refines-to {:my/B {:b1 {:$in [-20 50]}}
                                               :my/A {:a1 5}}}))))

    (testing "transitive refinements cannot be nested"
      (is (thrown? Exception
                   (pr/propagate sctx {:$type :my/C
                                       :$refines-to {:my/B {:b1 {:$in [20 50]}
                                                            :$refines-to {:my/A {:a1 5}}}}}))))

    (testing "disallow refinement bound on non-existant refinement path"
      (is (thrown? Exception
                   (pr/propagate sctx {:$type :my/A
                                       :$refines-to {:my/C {:c1 {:$in [20 50]}}}}))))))

(deftest test-push-if-value-with-refinement
  (let [sctx (ssa/spec-map-to-ssa
              '{:ws/B
                {:fields {:bw [:Maybe :Integer], :c2 [:Maybe [:Instance :ws/C]]}}
                :ws/C
                {:fields {:cw [:Maybe :Integer]}}
                :ws/D
                {:fields {:dx :Integer}
                 :refines-to {:ws/B {:expr {:$type :ws/B
                                            :bw (when (= 0 dx) 5)
                                            :c2 {:$type :ws/C :cw 6}}}}}
                :ws/E {:fields {:dx :Integer,
                                :bw [:Maybe :Integer],
                                :cw [:Maybe :Integer]},
                       :constraints [["$all" (= (refine-to {:dx dx, :$type :ws/D} :ws/B)
                                                {:bw bw,
                                                 :c2 (if-value cw
                                                               {:cw cw, :$type :ws/C}
                                                               $no-value),
                                                 :$type :ws/B})]]}})]
    (is (= {:$type :ws/E, :bw {:$in #{5 :Unset}}, :cw 6, :dx {:$in [-10 10]}}
           (pr/propagate sctx {:default-int-bounds [-10 10]} {:$type :ws/E})))))

(deftest test-lower-refine-to
  (let [sctx (ssa/spec-map-to-ssa
              '{:ws/A
                {:fields {:an :Integer}
                 :constraints [["a1" (< an 10)]]
                 :refines-to {:ws/B {:expr {:$type :ws/B :bn (+ 1 an)}}}}
                :ws/B
                {:fields {:bn :Integer}
                 :constraints [["b1" (< 0 bn)]]
                 :refines-to {:ws/C {:expr {:$type :ws/C :cn bn}}}}
                :ws/C
                {:fields {:cn :Integer}
                 :constraints [["c1" (= 0 (mod cn 2))]]}
                :ws/D
                {:fields {:dm :Integer, :dn :Integer}
                 :constraints [["d1" (= dm (get (refine-to {:$type :ws/A :an dn} :ws/C) :cn))]
                               ["d2" (= dn (get (refine-to {:$type :ws/B :bn dn} :ws/B) :bn))]
                               ["d3" (not= 72 (get {:$type :ws/A :an dn} :an))]]}})]
    (is (= '(let [v1 (+ 1 dn)
                  v2 {:>ws$B {:>ws$C {:cn v1, :$type :ws/C},
                              :bn v1,
                              :$type :ws/B},
                      :an dn,
                      :$type :ws/A}
                  v3 (get v2 :>ws$B)
                  v4 (get v3 :>ws$C)]
              (and (= dm (get v4 :cn))
                   (= dn (get {:>ws$C {:cn dn, :$type :ws/C},
                               :bn dn,
                               :$type :ws/B}
                              :bn))
                   (not= 72 (get v2 :an))))

           (-> (pr/lower-spec-refinements sctx (pr/make-rgraph sctx))
               :ws/D
               (ssa/spec-from-ssa)
               :constraints first second)))))

#_(deftest test-guarded-refinement
    (let [sctx (ssa/spec-map-to-ssa
                '{:my/A {:fields {:a1 :Integer}
                         :constraints {:a1_pos (< 0 a1)}}
                  :my/B {:fields {:b1 :Integer}
                         :refines-to {:my/A {:expr {:$type :my/A, :a1 (+ 10 b1)}}}}
                  :my/C {:fields {:c1 :Integer}
                         :refines-to {:my/B {:expr (when (< c1 5)
                                                     {:$type :my/B, :b1 (+ 5 c1)})}}}
                  :my/D {:refines-to {:my/A {:expr {:$type :my/A, :a1 10}}}}})]

      (s/with-fn-validation
        (is (= {:$type :my/C,
                :c1 {:$in [-14 1000]},
                :>:my$B {:$type [:Maybe :my/B],
                         :b1 {:$in [-9 9]},
                         :>:my$A {:$type :my/A,
                                  :a1 {:$in [1 19]}}}}

               (pr/lower-bound
                sctx
                {:$type :my/C,
                 :c1 {:$in [-14 1000]},
                 :$refines-to {:my/B {:$type [:Maybe :my/B],
                                      :b1 {:$in [-9 9]}}
                               :my/A {:$type :my/A,
                                      :a1 {:$in [1 19]}}}})))

        (is (thrown-with-msg? Exception #"No.*refinement path"
                              (pr/lower-bound
                               sctx
                               {:$type :my/C,
                                :$refines-to {:my/A {:$type :my/A}}}))))

      (is (= {:$type :my/C,
              :c1 {:$in [-14 1000]},
              :$refines-to {:my/B
                            {:$type [:Maybe :my/B],
                             :b1 {:$in [-9 9]},
                             :$refines-to {:my/A {:$type :my/A,
                                                  :a1 {:$in [1 19]}}}}}}

             (pr/propagate sctx {:$type :my/C})))))

#_(deftest test-refine-to-of-guarded
    (let [sctx (ssa/spec-map-to-ssa
                '{:my/A {:fields {:a1 :Integer}
                         :constraints {:a1_pos (< 0 a1)}}
                  :my/B {:fields {:b1 :Integer}
                         :refines-to {:my/A {:expr {:$type :my/A, :a1 (+ 10 b1)}}}}
                  :my/C {:fields {:c1 :Integer}
                         :refines-to {:my/B {:expr (when (< c1 5)
                                                     {:$type :my/B, :b1 (+ 5 c1)})}}}
                  :my/D {:fields {:a [:Instance :my/A]
                                  :c1 :Integer}
                         :constraints {:rto (= a (refine-to {:$type :my/C
                                                             :c1 c1}
                                                            :my/A))}}})]

      (s/with-fn-validation
        (is (= '{:my/A {:fields {:a1 :Integer}
                        :constraints {"$all" (< 0 a1)},
                        :refines-to {}},
                 :my/B {:fields {:b1 :Integer
                                 :>:my$A [:Instance :my/A]}
                        :constraints {"$all" (= >:my$A {:$type :my/A, :a1 (+ 10 b1)})},
                        :refines-to {}},
                 :my/C {:fields {:c1 :Integer
                                 :>:my$B [:Maybe [:Instance :my/B]]}
                        :constraints
                        {"$all" (= >:my$B
                                   (when (< c1 5)
                                     (let [v1 (+ 5 c1)]
                                       {:$type :my/B,
                                        :>:my$A {:$type :my/A, :a1 (+ 10 v1)},
                                        :b1 v1})))},
                        :refines-to {}},
                 :my/D {:fields {:a [:Instance :my/A]
                                 :c1 :Integer}
                        :constraints
                        {"$all" (let [v1 (get (let [v1 c1]
                                                {:$type :my/C,
                                                 :>:my$B (when (< v1 5)
                                                           (let [v2 (+ 5 v1)]
                                                             {:$type :my/B,
                                                              :>:my$A {:$type :my/A, :a1 (+ 10 v2)},
                                                              :b1 v2})),
                                                 :c1 v1})
                                              :>:my$B)
                                      v2 (get (if-value v1
                                                        v1
                                                        (error "No active refinement path"))
                                              :>:my$A)]
                                  (= a (if-value v2
                                                 v2
                                                 (error "No active refinement path"))))},
                        :refines-to {}}}
               (update-vals (pr/lower-spec-refinements sctx)
                            ssa/spec-from-ssa))))

      (is (= {:$type :my/D,
              :c1 {:$in [-14 4]}
              :a {:$type :my/A,
                  :a1 {:$in [1 19]}}}
             (pr/propagate sctx {:$type :my/D})))))
