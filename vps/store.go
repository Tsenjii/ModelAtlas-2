package main

import (
 "database/sql"
 "errors"
 "fmt"
 "os"
 "path/filepath"
 "strings"
 "time"
 _ "modernc.org/sqlite"
)

type Store struct { DB *sql.DB }
func OpenStore(path string) (*Store,error) {
 if path=="" {return nil,errors.New("empty database path")}
 if path!=":memory:" {if err:=os.MkdirAll(filepath.Dir(path),0700);err!=nil{return nil,err}}
 db,err:=sql.Open("sqlite",path);if err!=nil{return nil,err}
 db.SetMaxOpenConns(1); db.SetMaxIdleConns(1)
 for _,q:=range []string{"PRAGMA foreign_keys=ON","PRAGMA busy_timeout=5000","PRAGMA journal_mode=WAL"} {
  if _,err=db.Exec(q);err!=nil {db.Close();return nil,fmt.Errorf("pragma: %w",err)}
 }
 if _,err=db.Exec(schema);err!=nil {db.Close();return nil,fmt.Errorf("schema: %w",err)}
 return &Store{DB:db},nil
}
func (s *Store) Close()error{return s.DB.Close()}
func now()int64{return time.Now().UTC().UnixMilli()}
func validID(v string)bool {
 if len(v)<1||len(v)>128{return false}
 for _,r:=range v {if (r>='a'&&r<='z')||(r>='A'&&r<='Z')||(r>='0'&&r<='9')||strings.ContainsRune("._-/:",r){continue};return false}
 return true
}
func isConstraint(err error)bool{return err!=nil&&(strings.Contains(err.Error(),"constraint")||strings.Contains(err.Error(),"UNIQUE"))}
