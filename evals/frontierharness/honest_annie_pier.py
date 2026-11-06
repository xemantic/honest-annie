"""honest-annie as a Pier agent, run with `pier run --agent-import-path honest_annie_pier:HonestAnnie`."""
from pier.agents.base import BaseAgent
from pier.models.agent.network import NetworkAllowlist

from honest_annie_adapter import HonestAnnieAgent


class HonestAnnie(HonestAnnieAgent, BaseAgent):

    def __init__(self, *args, extra_env=None, **kwargs):
        # Pier passes the --agent-env of the job as extra_env, which its BaseAgent does not take
        self._extra_env = dict(extra_env or {})
        super().__init__(*args, **kwargs)

    def network_allowlist(self) -> NetworkAllowlist:
        # the only host the agent itself reaches, the task staying air-gapped
        return NetworkAllowlist(domains=[self.endpoint().host])
