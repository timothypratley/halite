;; Copyright (c) 2022 Viasat, Inc.
;; Licensed under the MIT license

(ns jibe.lib.graph)

;; dependency cycle detection

(defn- path-contains? [path x]
  (some #(= % x) path))

(defn- detect-cycle*
  "Path represents the sequence of nodes traversed so far in depth-first
  fashion. Checked is a set of nodes which have already been checked
  and confirmed to not be cyclic. This allows us to only check them
  once. x is the current node being checked. deps-f is the function to
  call to obtain the dependency edges coming from x. Will throw if a
  cycle is detected. Otherwise returns an updated set of all of the
  checked nodes."
  [path checked x deps-f]
  (cond
    (path-contains? path x) (throw (RuntimeException. (str "cycle detected: " (conj path x))))
    (contains? checked x) checked
    :default (loop [checked checked
                    [y & zs] (deps-f x)]
               (if y
                 (recur (detect-cycle* (conj path x) checked y deps-f) zs)
                 (conj checked x)))))

(defn detect-cycle [xs deps-f]
  "Treat each element of xs as a root to start walking from in the
  search for possible cycles. Call deps-f to obtain the dependency
  edges from a given node. Throws if a cycle is detected. Otherwise
  returns a set of all nodes checked."
  (loop [checked #{}
         [x & ys] xs]
    (if x
      (recur (detect-cycle* [] checked x deps-f) ys)
      checked)))
