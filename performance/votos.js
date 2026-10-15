import http from 'k6/http';
import { check } from 'k6';
if (!__ENV.PAUTA_ID) throw new Error('Informe PAUTA_ID de uma pauta com sessão aberta');
export const options = {
 scenarios: { votos: { executor: 'shared-iterations', vus: Number(__ENV.VUS || 50), iterations: Number(__ENV.VOTERS || 10000), maxDuration: '30m' } },
 thresholds: { http_req_failed: ['rate<0.01'], http_req_duration: ['p(95)<1000'], checks: ['rate>0.99'] },
};
import exec from 'k6/execution';
export default function () {
 const id = `load-${exec.scenario.iterationInTest}`;
 const result = http.post(`${__ENV.BASE_URL || 'http://localhost:8080'}/api/v1/pautas/${__ENV.PAUTA_ID}/votos`, JSON.stringify({ associadoId: id, voto: 'SIM' }), { headers: { 'Content-Type': 'application/json' } });
 check(result, { 'voto registrado': r => r.status === 204 });
}
