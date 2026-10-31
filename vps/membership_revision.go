package main

import (
	"database/sql"
)

func membershipRevision(tx *sql.Tx, v Selection) (int64, error) {
	var n int64
	err := tx.QueryRow("SELECT COALESCE(MAX(id),0) FROM membership_mutations WHERE scope_key=?", scopeKey(v)).Scan(&n)
	return n, err
}
func bumpMembership(tx *sql.Tx, v Selection) error {
	_, err := tx.Exec("INSERT INTO membership_mutations(scope_key,at) VALUES(?,?)", scopeKey(v), now())
	return err
}
