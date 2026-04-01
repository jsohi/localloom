import nextConfig from 'eslint-config-next';

const config = [{ ignores: ['e2e-results/**', 'test-results/**'] }, ...nextConfig];

export default config;
