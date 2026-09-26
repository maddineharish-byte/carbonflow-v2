import test from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { AuthBoundary } from './components/AuthBoundary.tsx';

const noOpLogin = async () => {};

test('AUTH BOUNDARY 1: loading state does not render protected application content', () => {
  const markup = renderToStaticMarkup(
    <AuthBoundary state="AUTH_LOADING" isLoading={false} error={null} onLogin={noOpLogin}>
      <div>PROTECTED_APPLICATION_CONTENT</div>
    </AuthBoundary>
  );

  assert.match(markup, /Checking authenticated session/);
  assert.doesNotMatch(markup, /PROTECTED_APPLICATION_CONTENT/);
});

test('AUTH BOUNDARY 2: unauthenticated state renders login and hides protected content', () => {
  const markup = renderToStaticMarkup(
    <AuthBoundary state="UNAUTHENTICATED" isLoading={false} error={null} onLogin={noOpLogin}>
      <div>PROTECTED_APPLICATION_CONTENT</div>
    </AuthBoundary>
  );

  assert.match(markup, /Sign in to CarbonFlow/);
  assert.match(markup, /auth-login-screen/);
  assert.doesNotMatch(markup, /PROTECTED_APPLICATION_CONTENT/);
  assert.doesNotMatch(markup, /admin@/);
});

test('AUTH BOUNDARY 3: authenticated state renders protected content without login', () => {
  const markup = renderToStaticMarkup(
    <AuthBoundary state="AUTHENTICATED" isLoading={false} error={null} onLogin={noOpLogin}>
      <div>PROTECTED_APPLICATION_CONTENT</div>
    </AuthBoundary>
  );

  assert.match(markup, /PROTECTED_APPLICATION_CONTENT/);
  assert.doesNotMatch(markup, /Sign in to CarbonFlow/);
});

test('AUTH BOUNDARY 4: login loading and error states are visible', () => {
  const loadingMarkup = renderToStaticMarkup(
    <AuthBoundary state="UNAUTHENTICATED" isLoading error={null} onLogin={noOpLogin}>
      <div>PROTECTED_APPLICATION_CONTENT</div>
    </AuthBoundary>
  );
  const errorMarkup = renderToStaticMarkup(
    <AuthBoundary state="UNAUTHENTICATED" isLoading={false} error="Invalid email or password." onLogin={noOpLogin}>
      <div>PROTECTED_APPLICATION_CONTENT</div>
    </AuthBoundary>
  );

  assert.match(loadingMarkup, /Signing in/);
  assert.match(errorMarkup, /Invalid email or password/);
  assert.doesNotMatch(loadingMarkup, /PROTECTED_APPLICATION_CONTENT/);
  assert.doesNotMatch(errorMarkup, /PROTECTED_APPLICATION_CONTENT/);
});
