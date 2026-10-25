package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestHealth(t *testing.T) {
	rec := httptest.NewRecorder()
	routes().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/health", nil))
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), `"UP"`) {
		t.Fatalf("unexpected response %d %s", rec.Code, rec.Body.String())
	}
}

func TestHello(t *testing.T) {
	rec := httptest.NewRecorder()
	routes().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/?name=Ada", nil))
	if !strings.Contains(rec.Body.String(), "Hello, Ada!") {
		t.Fatalf("unexpected body %s", rec.Body.String())
	}
}
