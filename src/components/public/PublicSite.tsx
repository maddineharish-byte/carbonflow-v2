/**
 * CarbonFlow — Public website dispatcher.
 *
 * Renders one of the public marketing pages, or the not-found page for any
 * unrecognised path. This component is the ONLY place a public path is turned
 * into content, which is what makes the guarantee simple to state and simple to
 * test:
 *
 *   no public path — and no unknown path — can render authenticated content,
 *   because authenticated content lives behind a completely different branch in
 *   `App.tsx` and neither this component nor any page it renders can reach it.
 *
 * The public pages are static: they make no API call, hold no session state, and
 * read no organization data.
 */

import React from 'react';
import { isPublicPath, normalizePath, usePageMeta } from '../../services/router.ts';
import { PublicLayout } from './PublicLayout.tsx';
import { NOT_FOUND_META, PUBLIC_PAGE_REGISTRY } from './pageRegistry.tsx';
import { NotFoundPage } from './NotFoundPage.tsx';

export interface PublicSiteProps {
  /** The current (already normalised) location path. */
  path: string;
}

export const PublicSite: React.FC<PublicSiteProps> = ({ path }) => {
  const normalized = normalizePath(path);
  const definition = isPublicPath(normalized)
    ? PUBLIC_PAGE_REGISTRY[normalized as keyof typeof PUBLIC_PAGE_REGISTRY]
    : undefined;

  const title = definition?.title ?? NOT_FOUND_META.title;
  const description = definition?.description ?? NOT_FOUND_META.description;
  usePageMeta(title, description);

  if (!definition) {
    return (
      <PublicLayout currentPath={normalized}>
        <NotFoundPage path={normalized} />
      </PublicLayout>
    );
  }

  const Page = definition.component;
  return (
    <PublicLayout currentPath={normalized}>
      <Page />
    </PublicLayout>
  );
};