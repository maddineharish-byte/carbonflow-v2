/**
 * CarbonFlow — Public registration page.
 *
 * This is the front end for the EXISTING backend registration endpoint
 * (`POST /api/v1/auth/register`, permitted without authentication in
 * `SecurityConfig`). It is the only signup implementation in the codebase and it
 * calls the same `api` client as every other view — there is no second client
 * and no second registration path.
 *
 * The page deliberately mirrors the backend contract exactly:
 *   - the created organization is PENDING_ACTIVATION and receives no tokens,
 *   - no session is established and no navigation into the workspace happens,
 *   - the confirmation tells the visitor that a platform administrator must
 *     review the organization before sign-in is possible.
 * A newly registered organization is never auto-activated from the client.
 */

import React, { useState } from 'react';
import { Building2, CheckCircle2, ShieldCheck } from 'lucide-react';
import { api, ApiError } from '../services/api.ts';
import { PublicLink, primaryButton, secondaryButton } from './public/PublicLayout.tsx';
import { PageHeader, Section } from './public/PublicUI.tsx';

/** Mirrors `AuthRequests.RegisterRequest`. Nothing here invents a field. */
interface RegistrationInput {
  organizationName: string;
  country: string;
  industry: string;
  taxId: string;
  fullName: string;
  email: string;
  password: string;
}

const EMPTY_FORM: RegistrationInput = {
  organizationName: '',
  country: '',
  industry: '',
  taxId: '',
  fullName: '',
  email: '',
  password: '',
};

/**
 * Client-side checks that duplicate the server's bean validation so the visitor
 * gets an immediate answer. The server remains the authority: nothing here
 * grants access, and the backend re-validates every field independently.
 */
function validate(input: RegistrationInput): string | null {
  if (!input.organizationName.trim()) return 'Organization name is required.';
  if (!input.fullName.trim()) return 'Your full name is required.';
  if (!input.email.trim()) return 'Email address is required.';
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(input.email.trim())) {
    return 'Enter a valid email address.';
  }
  if (!input.password) return 'Password is required.';
  if (input.password.length < 8) return 'Password must be at least 8 characters.';
  return null;
}

const inputClass =
  'w-full rounded-lg border border-line-strong bg-white px-3 py-2.5 text-sm text-ink-950 outline-none transition focus:border-brand-500';

export const RegisterView: React.FC = () => {
  const [form, setForm] = useState<RegistrationInput>(EMPTY_FORM);
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submittedEmail, setSubmittedEmail] = useState<string | null>(null);

  const update = (field: keyof RegistrationInput) => (
    event: React.ChangeEvent<HTMLInputElement>,
  ) => {
    const { value } = event.target;
    setForm((current) => ({ ...current, [field]: value }));
  };

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (isSubmitting) return;

    const validationError = validate(form);
    if (validationError) {
      setError(validationError);
      return;
    }

    setError(null);
    setIsSubmitting(true);
    try {
      await api.register({
        organizationName: form.organizationName.trim(),
        country: form.country.trim(),
        industry: form.industry.trim(),
        taxId: form.taxId.trim(),
        fullName: form.fullName.trim(),
        email: form.email.trim(),
        password: form.password,
      });
      // The backend issues no tokens for a pending organization, so nothing is
      // stored and no session is created here. The account becomes usable only
      // after platform approval.
      setSubmittedEmail(form.email.trim());
    } catch (caught) {
      if (caught instanceof ApiError) {
        setError(
          caught.status === 409
            ? 'An account with this email address already exists.'
            : caught.message,
        );
      } else {
        setError('Registration could not be submitted. Please try again.');
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  if (submittedEmail) {
    return (
      <Section>
        <div className="mx-auto w-full max-w-2xl">
          <div
            className="rounded-2xl border border-brand-200 bg-surface p-6 shadow-[0_8px_30px_rgba(20,26,22,0.06)] sm:p-8"
            data-testid="register-success"
          >
            <div className="flex items-start gap-3">
              <CheckCircle2 className="mt-0.5 h-6 w-6 shrink-0 text-brand-600" aria-hidden="true" />
              <div>
                <h1 className="text-xl font-bold tracking-tight text-ink-950 sm:text-2xl">
                  Registration received
                </h1>
                <p className="mt-3 text-sm leading-relaxed text-ink-700">
                  Your organization has been created and is currently{' '}
                  <strong className="font-semibold text-ink-950">pending activation</strong>. A
                  platform administrator must review and approve it before anyone can
                  sign in. No account credentials are active yet.
                </p>
              </div>
            </div>

            <dl className="mt-6 space-y-3 rounded-xl border border-line bg-sand-50 p-4 text-sm">
              <div>
                <dt className="text-xs font-semibold uppercase tracking-wider text-ink-400">
                  Registered account
                </dt>
                <dd className="mt-1 break-all text-ink-700">{submittedEmail}</dd>
              </div>
              <div>
                <dt className="text-xs font-semibold uppercase tracking-wider text-ink-400">
                  What happens next
                </dt>
                <dd className="mt-1 leading-relaxed text-ink-700">
                  Platform review, then activation. Once your organization is active,
                  return to <PublicLink to="/login" className="font-semibold text-brand-700 underline">Sign In</PublicLink>{' '}
                  with the account you just registered.
                </dd>
              </div>
            </dl>

            <div className="mt-6 flex flex-col gap-3 sm:flex-row">
              <PublicLink to="/" className={secondaryButton}>
                Back to home
              </PublicLink>
              <PublicLink to="/documentation" className={secondaryButton}>
                Read the documentation
              </PublicLink>
            </div>
          </div>
        </div>
      </Section>
    );
  }

  return (
    <Section>
      <div className="grid gap-10 lg:grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)] lg:items-start">
        <div className="mx-auto w-full max-w-2xl lg:mx-0">
          <PageHeader
            eyebrow="Get Started"
            title="Register your organization"
            lede="Create the organization and its first administrator account. The organization is registered in a pending state and reviewed by a platform administrator before sign-in is enabled."
          />

          <div className="mt-8">
            {error && (
              <div
                className="mb-5 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2.5 text-xs text-rose-700"
                role="alert"
                data-testid="register-error"
              >
                {error}
              </div>
            )}

            <form className="space-y-5" onSubmit={handleSubmit} noValidate>
              <fieldset className="space-y-5" disabled={isSubmitting}>
                <legend className="text-sm font-semibold text-ink-950">Organization</legend>

                <div>
                  <label htmlFor="reg-organization-name" className="mb-1.5 block text-xs font-medium text-ink-700">
                    Organization name <span aria-hidden="true">*</span>
                  </label>
                  <input
                    id="reg-organization-name"
                    data-testid="reg-organization-name"
                    type="text"
                    autoComplete="organization"
                    required
                    value={form.organizationName}
                    onChange={update('organizationName')}
                    className={inputClass}
                    placeholder="Registered legal or trading name"
                  />
                </div>

                <div className="grid gap-5 sm:grid-cols-2">
                  <div>
                    <label htmlFor="reg-country" className="mb-1.5 block text-xs font-medium text-ink-700">
                      Country
                    </label>
                    <input
                      id="reg-country"
                      data-testid="reg-country"
                      type="text"
                      autoComplete="country-name"
                      value={form.country}
                      onChange={update('country')}
                      className={inputClass}
                      placeholder="Country of registration"
                    />
                    <p className="mt-1.5 text-[11px] leading-relaxed text-ink-400">
                      Optional. CarbonFlow does not assume a jurisdiction.
                    </p>
                  </div>

                  <div>
                    <label htmlFor="reg-industry" className="mb-1.5 block text-xs font-medium text-ink-700">
                      Industry
                    </label>
                    <input
                      id="reg-industry"
                      data-testid="reg-industry"
                      type="text"
                      value={form.industry}
                      onChange={update('industry')}
                      className={inputClass}
                      placeholder="Sector"
                    />
                  </div>
                </div>

                <div>
                  <label htmlFor="reg-tax-id" className="mb-1.5 block text-xs font-medium text-ink-700">
                    Tax identifier
                  </label>
                  <input
                    id="reg-tax-id"
                    data-testid="reg-tax-id"
                    type="text"
                    value={form.taxId}
                    onChange={update('taxId')}
                    className={inputClass}
                    placeholder="Optional registration or tax identifier"
                  />
                </div>
              </fieldset>

              <fieldset className="space-y-5 border-t border-line pt-5" disabled={isSubmitting}>
                <legend className="text-sm font-semibold text-ink-950">Administrator account</legend>

                <div>
                  <label htmlFor="reg-full-name" className="mb-1.5 block text-xs font-medium text-ink-700">
                    Full name <span aria-hidden="true">*</span>
                  </label>
                  <input
                    id="reg-full-name"
                    data-testid="reg-full-name"
                    type="text"
                    autoComplete="name"
                    required
                    value={form.fullName}
                    onChange={update('fullName')}
                    className={inputClass}
                    placeholder="Your full name"
                  />
                </div>

                <div>
                  <label htmlFor="reg-email" className="mb-1.5 block text-xs font-medium text-ink-700">
                    Work email <span aria-hidden="true">*</span>
                  </label>
                  <input
                    id="reg-email"
                    data-testid="reg-email"
                    type="email"
                    autoComplete="username"
                    required
                    value={form.email}
                    onChange={update('email')}
                    className={inputClass}
                    placeholder="you@company.com"
                  />
                </div>

                <div>
                  <label htmlFor="reg-password" className="mb-1.5 block text-xs font-medium text-ink-700">
                    Password <span aria-hidden="true">*</span>
                  </label>
                  <input
                    id="reg-password"
                    data-testid="reg-password"
                    type="password"
                    autoComplete="new-password"
                    required
                    minLength={8}
                    value={form.password}
                    onChange={update('password')}
                    className={inputClass}
                    placeholder="At least 8 characters"
                  />
                  <p className="mt-1.5 text-[11px] leading-relaxed text-ink-400">
                    Stored as a BCrypt hash. CarbonFlow never stores or displays your
                    password.
                  </p>
                </div>
              </fieldset>

              <button
                id="reg-submit"
                data-testid="reg-submit"
                type="submit"
                disabled={isSubmitting}
                className={`${primaryButton} w-full py-3 disabled:cursor-not-allowed disabled:opacity-60`}
              >
                {isSubmitting && (
                  <span
                    className="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white"
                    aria-hidden="true"
                  />
                )}
                {isSubmitting ? 'Submitting registration…' : 'Register organization'}
              </button>
            </form>

            <p className="mt-6 text-sm text-ink-500">
              Already registered?{' '}
              <PublicLink to="/login" className="font-semibold text-brand-700 underline">
                Sign In
              </PublicLink>
            </p>
          </div>
        </div>

        <aside aria-labelledby="register-aside-heading" className="lg:sticky lg:top-24">
          <div className="rounded-2xl border border-line bg-surface p-6">
            <div className="flex items-center gap-2">
              <Building2 className="h-4 w-4 shrink-0 text-brand-600" aria-hidden="true" />
              <h2 id="register-aside-heading" className="text-sm font-semibold text-ink-950">
                What happens to your registration
              </h2>
            </div>
            <ol className="mt-5 space-y-4">
              {[
                {
                  title: 'Organization created — pending activation',
                  body: 'The organization and your administrator account are created together. No tokens are issued.',
                },
                {
                  title: 'Platform administrator review',
                  body: 'A platform administrator reviews the registration and records approval, rejection, or suspension.',
                },
                {
                  title: 'Activation, then sign-in',
                  body: 'Only once the organization is active can you sign in. Approval is a platform action and cannot be performed by your own company administrator.',
                },
              ].map((step, index) => (
                <li key={step.title} className="flex items-start gap-3">
                  <span
                    aria-hidden="true"
                    className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-md bg-brand-50 text-[11px] font-bold text-brand-700"
                  >
                    {index + 1}
                  </span>
                  <div>
                    <p className="text-sm font-semibold text-ink-950">{step.title}</p>
                    <p className="mt-1 text-sm leading-relaxed text-ink-500">{step.body}</p>
                  </div>
                </li>
              ))}
            </ol>

            <div className="mt-6 flex items-start gap-2 border-t border-line pt-5 text-[11px] leading-relaxed text-ink-400">
              <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0 text-brand-600" aria-hidden="true" />
              <span>
                CarbonFlow is a data and workflow management platform. It does not
                provide assurance, certification, or regulatory advice.
              </span>
            </div>
          </div>
        </aside>
      </div>
    </Section>
  );
};