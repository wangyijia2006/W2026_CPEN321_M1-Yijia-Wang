import { OAuth2Client } from 'google-auth-library';

export interface GoogleUser {
  id: string;
  firstName: string;
  lastName: string;
  displayName: string;
}

export type VerifyGoogleToken = (token: string) => Promise<GoogleUser>;

export function createGoogleVerifier(clientId: string, client = new OAuth2Client()): VerifyGoogleToken {
  return async (token) => {
    // The library checks Google's signature, audience, issuer and expiry.
    const ticket = await client.verifyIdToken({ idToken: token, audience: clientId });
    const payload = ticket.getPayload();
    if (!payload?.sub) throw new Error('Google token has no subject.');
    return {
      id: payload.sub,
      firstName: payload.given_name ?? '',
      lastName: payload.family_name ?? '',
      displayName: payload.name ?? '',
    };
  };
}
