"""honest-annie as a Harbor agent, run with `harbor run -a honest_annie_harbor:HonestAnnie`."""
from harbor.agents.base import BaseAgent

from honest_annie_adapter import HonestAnnieAgent


class HonestAnnie(HonestAnnieAgent, BaseAgent):
    pass
