;; Copyright (c) 2022 Viasat, Inc.
;; Licensed under the MIT license

(ns com.viasat.halite.var-type
  "User interface types"
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [com.viasat.halite.base :as base]
            [com.viasat.halite.envs :as envs]
            [com.viasat.halite.lib.fixed-decimal :as fixed-decimal]
            [com.viasat.halite.types :as types]
            [schema.core :as s]))

(set! *warn-on-reflection* true)

(def primitive-types (into ["String" "Integer"
                            "Boolean"]
                           (map #(str "Decimal" %) (range 1 (inc fixed-decimal/max-scale)))))

(s/defschema MandatoryVarType
  (s/conditional
   string? (apply s/enum primitive-types)
   vector? (s/constrained [(s/recursive #'MandatoryVarType)]
                          #(= 1 (count %))
                          "exactly one inner type")
   set? (s/constrained #{(s/recursive #'MandatoryVarType)}
                       #(= 1 (count %))
                       "exactly one inner type")
   :else types/NamespacedKeyword))

(defn maybe-type? [t]
  (and (vector? t) (= :Maybe (first t))))

(defn no-maybe [t]
  (if (maybe-type? t)
    (second t)
    t))

(s/defschema VarType
  (s/conditional
   maybe-type?
   [(s/one (s/enum :Maybe) :maybe) (s/one MandatoryVarType :inner)]

   :else MandatoryVarType))

(s/defn optional-var-type? :- s/Bool
  [var-type :- VarType]
  (and (vector? var-type) (= :Maybe (first var-type))))

(s/defschema SpecVars {types/BareKeyword VarType})

(s/defschema UserSpecInfo
  (assoc envs/HaliteSpecInfo
         (s/optional-key :spec-vars) {types/BareKeyword VarType}
         (s/optional-key :constraints) {base/UserConstraintName s/Any}))

(s/defschema SpecMap
  {types/NamespacedKeyword UserSpecInfo})

;;

(s/defn lookup-spec-abstract? :- (s/maybe Boolean)
  "Lookup whether the given spec is abstract. Produce nil if the spec does not exist. This function
  avoids enforcing a schema on the spec specifically so that it can be used when the spec
  environment is being converted between formats."
  [senv :- (s/protocol envs/SpecEnv), spec-id :- types/NamespacedKeyword]
  (when-let [spec (envs/lookup-spec* senv spec-id)]
    (boolean (:abstract? spec))))

(s/defn halite-type-from-var-type :- types/HaliteType
  [senv :- (s/protocol envs/SpecEnv)
   var-type :- VarType]
  (when (coll? var-type)
    (let [n (if (and (vector? var-type) (= :Maybe (first var-type))) 2 1)]
      (when (not= n (count var-type))
        (throw (ex-info "Must contain exactly one inner type"
                        {:var-type var-type})))))
  (let [check-mandatory #(if (optional-var-type? %)
                           (throw (ex-info "Set and vector types cannot have optional inner types"
                                           {:var-type %}))
                           %)]
    (cond
      (string? var-type)
      (cond
        (#{"Integer" "String" "Boolean"} var-type) (keyword var-type)
        (str/starts-with? var-type "Decimal") (types/decimal-type (Long/parseLong (subs var-type 7)))
        :default (throw (ex-info (format "Unrecognized primitive type: %s" var-type) {:var-type var-type})))

      (optional-var-type? var-type)
      [:Maybe (halite-type-from-var-type senv (second var-type))]

      (vector? var-type)
      [:Vec (halite-type-from-var-type senv (check-mandatory (first var-type)))]

      (set? var-type)
      [:Set (halite-type-from-var-type senv (check-mandatory (first var-type)))]

      (types/namespaced-keyword? var-type)
      (let [abstract? (lookup-spec-abstract? senv var-type)]
        (if (nil? abstract?)
          (throw (ex-info (format "Spec not found: %s" var-type) {:var-type var-type}))
          (if abstract?
            (types/abstract-spec-type var-type)
            (types/concrete-spec-type var-type))))

      :else (throw (ex-info "Invalid spec variable type" {:var-type var-type})))))

(s/defn halite-type-from-var-type-if-needed :- types/HaliteType
  [senv :- (s/protocol envs/SpecEnv)
   var-type :- s/Any]

  ;; TODO: remove this once we are swtiched over to use halite types pervasively
  (if (nil? (s/check types/HaliteType var-type))
    var-type
    (halite-type-from-var-type senv var-type)))

(s/defn to-halite-spec :- (s/maybe envs/HaliteSpecInfo)
  "Create specs with halite types from specs with var types"
  [senv :- (s/protocol envs/SpecEnv)
   spec-info :- s/Any]
  (when spec-info
    (if (seq (:spec-vars spec-info))
      (update spec-info :spec-vars update-vals (partial halite-type-from-var-type-if-needed senv))
      spec-info)))

(s/defn to-halite-spec-env :- (s/protocol envs/SpecEnv)
  "If the spec env is a map object, then update the values to use halite types. If it is not a map
  then rely on the lookup function to perform the conversion."
  [senv :- (s/protocol envs/SpecEnv)]
  (if (map? senv)
    (update-vals senv (partial to-halite-spec senv))
    (reify envs/SpecEnv
      (lookup-spec* [_ spec-id]
        (to-halite-spec senv (envs/lookup-spec* senv spec-id))))))