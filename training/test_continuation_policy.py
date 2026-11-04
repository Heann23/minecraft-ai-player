"""Probability-law fixtures only; no game, training or ability evidence."""
import unittest
import numpy as np
from continuation_policy import scores, relative_entropy

class ContinuationTests(unittest.TestCase):
    def test_relative_entropy_does_not_reward_switch_option_multiplicity(self):
        for n in (2,8,128):
            frame=self.frame(n);logits=scores(frame,np.zeros(n));p=np.exp(logits-logits.max());p/=p.sum()
            value,gradient=relative_entropy(frame,p)
            self.assertAlmostEqual(value,np.log(2))
            np.testing.assert_allclose(gradient,0,atol=1e-14)
            categorical=p*(np.log(p)-float(p@np.log(p)))
            if n>2:self.assertGreater(categorical[0],0)

    def test_relative_entropy_gradient_and_inactive_flat_equivalence(self):
        frame=self.frame(7);frame['options'][1]['inputId']='0';logits=scores(frame,np.arange(7)*.13)
        def calculate(z):
            p=np.exp(z-z.max());p/=p.sum();return relative_entropy(frame,p)
        value,gradient=calculate(logits)
        for index in range(7):
            plus=logits.copy();minus=logits.copy();plus[index]+=1e-5;minus[index]-=1e-5
            self.assertAlmostEqual(gradient[index],-(calculate(plus)[0]-calculate(minus)[0])/2e-5,places=8)
        frame['state'][400]=0;p=np.exp(logits-logits.max());p/=p.sum();value,gradient=relative_entropy(frame,p)
        entropy=-float(p@np.log(p));self.assertAlmostEqual(value,entropy)
        np.testing.assert_allclose(gradient,p*(np.log(p)+entropy))

    def frame(self, count, running=True):
        state=[0.0]*432;state[400]=state[401]=int(running)
        options=[]
        for i in range(count):
            f=[0.0]*256;f[55]=int(i==0);options.append({"features":f,"inputId":str(i)})
        return {"state":state,"options":options}

    def test_neutral_continue_mass_is_independent_of_switch_multiplicity(self):
        for n in (2,8,128):
            original=np.zeros(n);logits=scores(self.frame(n),original)
            p=np.exp(logits-logits.max());p/=p.sum()
            self.assertAlmostEqual(p[0],0.5)
            np.testing.assert_array_equal(original,0)

    def test_no_running_action_preserves_flat_law_and_switch_can_win(self):
        original=np.array([0.,10.,0.,0.])
        np.testing.assert_array_equal(scores(self.frame(4,False),original),original)
        adjusted=scores(self.frame(4),original)
        self.assertEqual(int(np.argmax(adjusted)),1)

    def test_conditional_switch_probabilities_are_preserved(self):
        original=np.array([0.,1.,2.,3.]);adjusted=scores(self.frame(4),original)
        p=np.exp(adjusted-adjusted.max());q=np.exp(original-original.max())
        np.testing.assert_allclose(p[1:]/p[1:].sum(),q[1:]/q[1:].sum())

    def test_compatible_duration_variants_share_continue_mass(self):
        frame=self.frame(6);frame['options'][1]['inputId']='0'
        logits=scores(frame,np.zeros(6));p=np.exp(logits-logits.max());p/=p.sum()
        np.testing.assert_allclose(p[:2],[0.25,0.25])
        np.testing.assert_allclose(p[2:],[0.125]*4)
        del frame['options'][2]['inputId']
        with self.assertRaisesRegex(ValueError,'identities'):scores(frame,np.zeros(6))
