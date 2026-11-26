"""Numerical/contract fixtures only. These tests are not Minecraft training or performance evidence."""
import math
import json
from pathlib import Path
import tempfile
import unittest
import uuid
import threading
from unittest import mock

import numpy as np
import brain_session

from rl_actor import ActorCritic, inputs, generalized_advantages, STATE_SIZE, ACTION_SIZE, ADVANTAGE_STD_FLOOR
from train_paper_ppo import PaperTrainer, SCHEMA, CONTROL_SCHEMA, resume_checkpoint, serve, exchange_request, validate_transition


def frame():
    state = [0.0] * STATE_SIZE
    state[0] = 0.3
    actions = []
    for name, value in (("left", -0.5), ("right", 0.5)):
        features = [0.0] * ACTION_SIZE
        features[0] = value
        actions.append({"id": name, "features": features})
    return {"world": str(uuid.UUID(int=1)), "serverTick": 10, "state": state, "options": actions, "complete": True}


def row(model, session="a", sequence=0, reward=1, terminal=True):
    observed = frame()
    probabilities, value, _ = model.evaluate(observed)
    return {"session": session, "sequence": sequence, "frame": observed, "index": 1,
            "logProbability": math.log(float(probabilities[1])), "value": value,
            "modelVersion": model.version, "reward": reward, "elapsedTicks": 5,
            "terminated": terminal, "truncated": False, "nextValue": 0,
            "appliedTick": observed["serverTick"], "nextWorld": observed["world"], "nextState": observed["state"]}


class ActorTests(unittest.TestCase):
    def test_relative_exploration_ppo_gradient_and_optimizer_restore(self):
        import copy
        from continuation_policy import SELECTION
        model=ActorCritic(31,hidden=4);model.selection=SELECTION;model.exploration='continue-switch-relative/v1'
        observed=frame();observed['state'][400]=observed['state'][401]=1;observed['options'][0]['features'][55]=1
        for index in range(3):
            option=copy.deepcopy(observed['options'][1]);option['id']='extra'+str(index);option['features'][0]=index+1.;observed['options'].append(option)
        for option in observed['options']:option['inputId']=option['id']
        probabilities,value,_=model.evaluate(observed)
        sample=dict(row(model),frame=observed,index=0,logProbability=math.log(float(probabilities[0])),value=value)
        advantages=np.array([.7]);targets=np.array([value+1]);gradients,_=model.gradients([sample],advantages,targets,entropy_weight=.03)
        for name,index in [('actor_w',(0,400)),('actor_w',(0,STATE_SIZE+55)),('actor_out',(0,))]:
            original=model.params[name][index];eps=1e-5;model.params[name][index]=original+eps
            plus=model.gradients([sample],advantages,targets,entropy_weight=.03)[1]['loss'];model.params[name][index]=original-eps
            minus=model.gradients([sample],advantages,targets,entropy_weight=.03)[1]['loss'];model.params[name][index]=original
            self.assertAlmostEqual(gradients[name][index],(plus-minus)/(2*eps),places=6)
        model.fit([sample]);saved=model.optimizer_state();restored=ActorCritic.from_optimizer_state(saved)
        self.assertEqual(restored.exploration,'continue-switch-relative/v1');np.testing.assert_array_equal(restored.evaluate(observed)[0],model.evaluate(observed)[0])
        bad=copy.deepcopy(saved);bad['exploration']='unknown'
        with self.assertRaises(ValueError):ActorCritic.from_optimizer_state(bad)

    def test_zero_goal_residual_preserves_original_state_and_has_correct_ppo_gradient(self):
        import copy
        model=ActorCritic(31,hidden=4);model.fit([row(model)])
        observed=frame();observed['state'][216]=1
        before=copy.deepcopy(model.optimizer_state());probabilities,value,_=model.evaluate(observed)
        model.enable_goal_interaction();ActorCritic.verify_zero_goal_extension(before,model.optimizer_state())
        np.testing.assert_array_equal(model.evaluate(observed)[0],probabilities)
        self.assertEqual(model.value(observed['state'])[0],value)
        sample=dict(row(model),frame=observed,logProbability=math.log(float(probabilities[1])),value=value)
        advantages=np.array([.7]);targets=np.array([value+1])
        gradients,_=model.gradients([sample],advantages,targets,entropy_weight=.03)
        key=(8,0);original=model.params['goal_action_w'][key];eps=1e-5
        model.params['goal_action_w'][key]=original+eps
        plus=model.gradients([sample],advantages,targets,entropy_weight=.03)[1]['loss']
        model.params['goal_action_w'][key]=original-eps
        minus=model.gradients([sample],advantages,targets,entropy_weight=.03)[1]['loss']
        model.params['goal_action_w'][key]=original
        self.assertAlmostEqual(gradients['goal_action_w'][key],(plus-minus)/(2*eps),places=6)
        self.assertNotEqual(gradients['goal_action_w'][key],0)
        model.fit([sample]);restored=ActorCritic.from_optimizer_state(model.optimizer_state())
        self.assertEqual(restored.interaction,'goal-action-bilinear/v1')
        np.testing.assert_array_equal(restored.evaluate(observed)[0],model.evaluate(observed)[0])
        self.assertEqual(model.artifact(environment_steps=1,training_run='numerical-fixture',rollout_sha256='0'*64)['schema'],'minecraft-rl-actor/v3')
        with self.assertRaises(ValueError):ActorCritic.verify_zero_goal_extension(before,model.optimizer_state())

    def test_state_action_bilinear_zero_residual_and_ppo_gradients(self):
        import copy
        model = ActorCritic(31, hidden=4)
        model.fit([row(model)])
        observed = frame()
        observed['state'][415] = 0.25
        before = copy.deepcopy(model.optimizer_state())
        rng_before = copy.deepcopy(model.rng.bit_generator.state)
        probabilities, value, _ = model.evaluate(observed)
        model.enable_state_action_interaction(seed=77, rank=4)
        ActorCritic.verify_zero_state_action_extension(before, model.optimizer_state())
        self.assertEqual(model.rng.bit_generator.state, rng_before)
        np.testing.assert_array_equal(model.evaluate(observed)[0], probabilities)
        self.assertEqual(model.value(observed['state'])[0], value)

        sample = dict(row(model), frame=observed, logProbability=math.log(float(probabilities[1])), value=value)
        advantages = np.array([0.7])
        targets = np.array([value + 1.0])
        gradients, _ = model.gradients([sample], advantages, targets, entropy_weight=0.03)
        for index in ((0, 0), (0, 2)):
            original = model.params['state_action_a'][index]
            eps = 1e-5
            model.params['state_action_a'][index] = original + eps
            plus = model.gradients([sample], advantages, targets, entropy_weight=0.03)[1]['loss']
            model.params['state_action_a'][index] = original - eps
            minus = model.gradients([sample], advantages, targets, entropy_weight=0.03)[1]['loss']
            model.params['state_action_a'][index] = original
            self.assertAlmostEqual(gradients['state_action_a'][index], (plus - minus) / (2 * eps), places=6)
        self.assertNotEqual(gradients['state_action_a'][0, 0], 0.0)

        model.fit([sample])
        post_probs, post_val, _ = model.evaluate(observed)
        post_sample = dict(row(model), frame=observed, logProbability=math.log(float(post_probs[1])), value=post_val)
        post_grads, _ = model.gradients([post_sample], advantages, targets, entropy_weight=0.03)
        for pname, index in (('state_action_a', (0, 1)), ('state_action_s', (415, 1)), ('state_action_b', (1,))):
            original = model.params[pname][index]
            eps = 1e-5
            model.params[pname][index] = original + eps
            plus = model.gradients([post_sample], advantages, targets, entropy_weight=0.03)[1]['loss']
            model.params[pname][index] = original - eps
            minus = model.gradients([post_sample], advantages, targets, entropy_weight=0.03)[1]['loss']
            model.params[pname][index] = original
            self.assertAlmostEqual(post_grads[pname][index], (plus - minus) / (2 * eps), places=6)
            self.assertNotEqual(post_grads[pname][index], 0.0)

        decay_model = ActorCritic(31, hidden=4)
        decay_model.enable_state_action_interaction(seed=77, rank=4)
        decay_model.params['state_action_a'].fill(0.5)
        decay_model.params['state_action_b'].fill(0.4)
        s_before = decay_model.params['state_action_s'].copy()
        b_before = decay_model.params['state_action_b'].copy()
        a_before = decay_model.params['state_action_a'].copy()
        zero_grads = {key: np.zeros_like(val) for key, val in decay_model.params.items()}
        decay_model.apply(zero_grads)
        np.testing.assert_allclose(decay_model.params['state_action_s'], s_before * 0.9997, rtol=0, atol=1e-12)
        np.testing.assert_allclose(decay_model.params['state_action_b'], b_before, rtol=0, atol=1e-12)
        np.testing.assert_allclose(decay_model.params['state_action_a'], a_before * 0.9995, rtol=0, atol=1e-12)

        restored = ActorCritic.from_optimizer_state(model.optimizer_state())
        self.assertEqual(restored.interaction, 'state-action-bilinear/v1')
        np.testing.assert_array_equal(restored.evaluate(observed)[0], model.evaluate(observed)[0])
        artifact = model.artifact(environment_steps=1, training_run='numerical-fixture', rollout_sha256='0' * 64)
        self.assertEqual(artifact['schema'], 'minecraft-rl-actor/v4')
        self.assertEqual(len(artifact['stateActionStateBias']), 4)
        with self.assertRaises(ValueError):
            ActorCritic.verify_zero_state_action_extension(before, model.optimizer_state())

    def test_advantage_variance_floor_dampens_near_zero_batches_without_stalling_real_signal(self):
        def pair_rows(model, session, magnitude):
            observed = frame()
            probabilities, value, _ = model.evaluate(observed)
            rows = []
            for seq, (idx, rew) in enumerate(((0, -magnitude), (1, magnitude), (0, -magnitude), (1, magnitude))):
                rows.append({
                    "session": session, "sequence": seq, "frame": observed, "index": idx,
                    "logProbability": math.log(float(probabilities[idx])), "value": value,
                    "modelVersion": model.version, "reward": rew, "elapsedTicks": 5,
                    "terminated": True, "truncated": False, "nextValue": 0,
                    "appliedTick": observed["serverTick"], "nextWorld": observed["world"],
                    "nextState": observed["state"],
                })
            return rows

        noise_model = ActorCritic(43, hidden=4)
        signal_model = ActorCritic(43, hidden=4)
        # Warm up identical Adam second moments (reflecting resumed PPO scale), then clear first-moment carryover.
        noise_model.fit(pair_rows(noise_model, "w", 2.0), epochs=1, minibatch=4)
        signal_model.fit(pair_rows(signal_model, "w", 2.0), epochs=1, minibatch=4)
        for m in (noise_model, signal_model):
            for key in m.first:
                m.first[key].fill(0.0)

        init_out = noise_model.params["actor_out"].copy()
        noise_rows = pair_rows(noise_model, "s", 0.002)
        signal_rows = pair_rows(signal_model, "s", 2.0)

        noise_adv, noise_ret = generalized_advantages(noise_rows)
        signal_adv, signal_ret = generalized_advantages(signal_rows)
        self.assertEqual(ADVANTAGE_STD_FLOOR, 1.0)
        noise_adv = (noise_adv - noise_adv.mean()) / math.hypot(float(noise_adv.std()), ADVANTAGE_STD_FLOOR)
        signal_adv = (signal_adv - signal_adv.mean()) / math.hypot(float(signal_adv.std()), ADVANTAGE_STD_FLOOR)
        noise_g, _ = noise_model.gradients(noise_rows, noise_adv, noise_ret, entropy_weight=0.0)
        signal_g, _ = signal_model.gradients(signal_rows, signal_adv, signal_ret, entropy_weight=0.0)
        self.assertGreater(float(np.linalg.norm(signal_g["actor_out"])), 1e-4)
        self.assertLess(
            float(np.linalg.norm(noise_g["actor_out"])),
            0.05 * float(np.linalg.norm(signal_g["actor_out"])),
        )

        noise_model.fit(noise_rows, epochs=1, minibatch=4)
        signal_model.fit(signal_rows, epochs=1, minibatch=4)
        noise_move = float(np.linalg.norm(noise_model.params["actor_out"] - init_out))
        signal_move = float(np.linalg.norm(signal_model.params["actor_out"] - init_out))
        self.assertGreater(signal_move, 1e-4)
        self.assertLess(noise_move, 0.1 * signal_move)

    def test_state_action_bilinear_centers_gate_and_scales_action_step_by_sqrt_rank(self):
        model = ActorCritic(51, hidden=4)
        model.enable_state_action_interaction(seed=19, rank=4)
        model.params["state_action_s"].fill(0.0)
        model.params["state_action_s"][432, :] = 1.0
        model.params["state_action_s"][415, :] = 1.5
        rows = []
        for seq, tier in enumerate((0.0, 0.5, 0.0, 0.5)):
            observed = frame()
            observed["state"][432] = 1.0
            observed["state"][415] = tier
            probs, value, _ = model.evaluate(observed)
            chosen = seq % 2
            rows.append({
                "session": "c", "sequence": seq, "frame": observed, "index": chosen,
                "logProbability": math.log(float(probs[chosen])), "value": value,
                "modelVersion": model.version, "reward": 0.0, "elapsedTicks": 5,
                "terminated": True, "truncated": False, "nextValue": 0,
                "appliedTick": observed["serverTick"], "nextWorld": observed["world"],
                "nextState": observed["state"],
            })
        states = np.asarray([r["frame"]["state"] for r in rows], dtype=np.float64)
        before_mean = float(np.abs(np.mean(states @ model.params["state_action_s"] + model.params["state_action_b"], axis=0)).mean())
        model.fit(rows, epochs=1, minibatch=4)
        after_mean = float(np.abs(np.mean(states @ model.params["state_action_s"] + model.params["state_action_b"], axis=0)).mean())
        contrast = float(np.abs((states[1] - states[0]) @ model.params["state_action_s"]).mean())
        self.assertLess(after_mean, 0.85 * before_mean)
        self.assertGreater(contrast, 0.5)

        m4 = ActorCritic(53, hidden=4)
        m16 = ActorCritic(53, hidden=4)
        m4.enable_state_action_interaction(seed=23, rank=4)
        m16.enable_state_action_interaction(seed=23, rank=16)
        g4 = {k: np.zeros_like(v) for k, v in m4.params.items()}
        g16 = {k: np.zeros_like(v) for k, v in m16.params.items()}
        g4["state_action_a"].fill(1e-3)
        g16["state_action_a"].fill(1e-3)
        m4.apply(g4)
        m16.apply(g16)
        d4 = float(np.abs(m4.params["state_action_a"]).mean())
        d16 = float(np.abs(m16.params["state_action_a"]).mean())
        self.assertGreater(d4, 1e-4)
        self.assertAlmostEqual(d16 / d4, 0.5, places=6)

    def test_temporal_ppo_gradient_matches_changed_sampling_law(self):
        import copy
        from continuation_policy import SELECTION
        model=ActorCritic(31,hidden=4);model.selection=SELECTION
        observed=frame();observed['state'][400]=observed['state'][401]=1
        observed['options'][0]['features'][55]=1
        for i in range(3):
            option=copy.deepcopy(observed['options'][1]);option['id']='extra'+str(i)
            option['features'][0]=i+1.;observed['options'].append(option)
        for option in observed['options']:option['inputId']=option['id']
        observed['options'][1]['inputId']=observed['options'][0]['inputId']
        probs,value,_=model.evaluate(observed)
        sample=dict(row(model),frame=observed,index=0,logProbability=math.log(float(probs[0])),value=value)
        advantage=np.array([0.7]);target=np.array([value+1])
        gradients,_=model.gradients([sample],advantage,target,entropy_weight=0.03)
        for name,index in [('actor_w',(0,400)),('actor_w',(0,STATE_SIZE+55)),('actor_out',(0,)),('value_out',(0,))]:
            original=model.params[name][index];eps=1e-5
            model.params[name][index]=original+eps
            plus=model.gradients([sample],advantage,target,entropy_weight=0.03)[1]['loss']
            model.params[name][index]=original-eps
            minus=model.gradients([sample],advantage,target,entropy_weight=0.03)[1]['loss']
            model.params[name][index]=original
            self.assertAlmostEqual(gradients[name][index],(plus-minus)/(2*eps),places=6)
        model.fit([sample]);restored=ActorCritic.from_optimizer_state(model.optimizer_state())
        self.assertEqual(restored.selection,SELECTION)
        self.assertEqual(restored.artifact(environment_steps=1,training_run='numerical-fixture',rollout_sha256='0'*64)['schema'],'minecraft-rl-actor/v2')

    def test_v9_world_context_upgrade_preserves_optimizer_goal_interaction_rng_and_old_outputs(self):
        import copy
        for with_goal in (False, True):
            model = ActorCritic(41, hidden=4)
            if with_goal:
                model.enable_goal_interaction()
            model.fit([row(model)])
            legacy = copy.deepcopy(model.optimizer_state()); legacy["contract"] = "minecraft-primitive-candidates/v9"
            for group in ("params", "first", "second"):
                actor = np.asarray(legacy[group]["actor_w"])
                legacy[group]["actor_w"] = np.concatenate((actor[:, :432], actor[:, STATE_SIZE:]), axis=1).tolist()
                legacy[group]["value_w"] = np.asarray(legacy[group]["value_w"])[:, :432].tolist()
            with self.assertRaises(ValueError):
                ActorCritic.from_optimizer_state(legacy)
            upgraded = ActorCritic.upgrade_legacy_context(legacy)
            for group in ("params", "first", "second"):
                actor = getattr(upgraded, group)["actor_w"]
                np.testing.assert_array_equal(actor[:, 432:STATE_SIZE], 0)
                np.testing.assert_array_equal(np.concatenate((actor[:, :432], actor[:, STATE_SIZE:]), axis=1), legacy[group]["actor_w"])
                value_w = getattr(upgraded, group)["value_w"]
                np.testing.assert_array_equal(value_w[:, 432:STATE_SIZE], 0)
                np.testing.assert_array_equal(value_w[:, :432], legacy[group]["value_w"])
                if with_goal:
                    np.testing.assert_array_equal(getattr(upgraded, group)["goal_action_w"], legacy[group]["goal_action_w"])
            self.assertEqual(upgraded.rng.bit_generator.state, legacy["rng"])
            observed = frame(); observed["state"][208] = observed["state"][217] = 1.0
            before, value, _ = upgraded.evaluate(observed)
            observed["state"][432:] = [0.65] * (STATE_SIZE - 432)
            after, next_value, _ = upgraded.evaluate(observed)
            np.testing.assert_array_equal(before, after); self.assertEqual(value, next_value)

    def test_v8_material_impact_upgrade_preserves_optimizer_goal_interaction_rng_and_old_outputs(self):
        import copy
        for with_goal in (False, True):
            model = ActorCritic(37, hidden=4)
            if with_goal:
                model.enable_goal_interaction()
            model.fit([row(model)])
            legacy = copy.deepcopy(model.optimizer_state()); legacy["contract"] = "minecraft-primitive-candidates/v8"
            for group in ("params", "first", "second"):
                actor = np.asarray(legacy[group]["actor_w"])
                legacy[group]["actor_w"] = np.concatenate((actor[:, :432], actor[:, STATE_SIZE:STATE_SIZE + 288]), axis=1).tolist()
                legacy[group]["value_w"] = np.asarray(legacy[group]["value_w"])[:, :432].tolist()
                if with_goal:
                    cross = np.asarray(legacy[group]["goal_action_w"])
                    legacy[group]["goal_action_w"] = cross[:, :288].tolist()
            with self.assertRaises(ValueError):
                ActorCritic.from_optimizer_state(legacy)
            upgraded = ActorCritic.upgrade_legacy_context(legacy)
            for group in ("params", "first", "second"):
                actor = getattr(upgraded, group)["actor_w"]
                np.testing.assert_array_equal(actor[:, 432:STATE_SIZE], 0)
                np.testing.assert_array_equal(actor[:, STATE_SIZE + 288:], 0)
                np.testing.assert_array_equal(np.concatenate((actor[:, :432], actor[:, STATE_SIZE:STATE_SIZE + 288]), axis=1), legacy[group]["actor_w"])
                value_w = getattr(upgraded, group)["value_w"]
                np.testing.assert_array_equal(value_w[:, 432:STATE_SIZE], 0)
                np.testing.assert_array_equal(value_w[:, :432], legacy[group]["value_w"])
                if with_goal:
                    cross = getattr(upgraded, group)["goal_action_w"]
                    np.testing.assert_array_equal(cross[:, 288:], 0)
                    np.testing.assert_array_equal(cross[:, :288], legacy[group]["goal_action_w"])
            self.assertEqual(upgraded.rng.bit_generator.state, legacy["rng"])
            observed = frame(); observed["state"][208] = observed["state"][217] = 1.0
            before, value, _ = upgraded.evaluate(observed)
            observed["state"][432:] = [0.75] * (STATE_SIZE - 432)
            for option in observed["options"]:
                option["features"][288:] = [0.75] * (ACTION_SIZE - 288)
            after, next_value, _ = upgraded.evaluate(observed)
            np.testing.assert_array_equal(before, after); self.assertEqual(value, next_value)

    def test_v7_candidate_action_upgrade_preserves_optimizer_goal_interaction_rng_and_old_outputs(self):
        import copy
        for with_goal in (False, True):
            model = ActorCritic(31, hidden=4)
            if with_goal:
                model.enable_goal_interaction()
            model.fit([row(model)])
            legacy = copy.deepcopy(model.optimizer_state()); legacy["contract"] = "minecraft-primitive-candidates/v7"
            for group in ("params", "first", "second"):
                actor = np.asarray(legacy[group]["actor_w"])
                legacy[group]["actor_w"] = np.concatenate((actor[:, :432], actor[:, STATE_SIZE:STATE_SIZE + 256]), axis=1).tolist()
                legacy[group]["value_w"] = np.asarray(legacy[group]["value_w"])[:, :432].tolist()
                if with_goal:
                    cross = np.asarray(legacy[group]["goal_action_w"])
                    legacy[group]["goal_action_w"] = cross[:, :256].tolist()
            with self.assertRaises(ValueError):
                ActorCritic.from_optimizer_state(legacy)
            upgraded = ActorCritic.upgrade_legacy_context(legacy)
            for group in ("params", "first", "second"):
                actor = getattr(upgraded, group)["actor_w"]
                np.testing.assert_array_equal(actor[:, 432:STATE_SIZE], 0)
                np.testing.assert_array_equal(actor[:, STATE_SIZE + 256:], 0)
                np.testing.assert_array_equal(np.concatenate((actor[:, :432], actor[:, STATE_SIZE:STATE_SIZE + 256]), axis=1), legacy[group]["actor_w"])
                value_w = getattr(upgraded, group)["value_w"]
                np.testing.assert_array_equal(value_w[:, 432:STATE_SIZE], 0)
                np.testing.assert_array_equal(value_w[:, :432], legacy[group]["value_w"])
                if with_goal:
                    cross = getattr(upgraded, group)["goal_action_w"]
                    np.testing.assert_array_equal(cross[:, 256:], 0)
                    np.testing.assert_array_equal(cross[:, :256], legacy[group]["goal_action_w"])
            self.assertEqual(upgraded.rng.bit_generator.state, legacy["rng"])
            observed = frame(); observed["state"][208] = observed["state"][217] = 1.0
            before, value, _ = upgraded.evaluate(observed)
            observed["state"][432:] = [0.75] * (STATE_SIZE - 432)
            for option in observed["options"]:
                option["features"][256:] = [0.75] * (ACTION_SIZE - 256)
            after, next_value, _ = upgraded.evaluate(observed)
            np.testing.assert_array_equal(before, after); self.assertEqual(value, next_value)

    def test_v6_execution_upgrade_preserves_optimizer_rng_and_old_outputs(self):
        import copy
        model = ActorCritic(31, hidden=4)
        model.fit([row(model)])
        legacy = copy.deepcopy(model.optimizer_state()); legacy["contract"] = "minecraft-primitive-candidates/v6"
        for group in ("params", "first", "second"):
            actor = np.asarray(legacy[group]["actor_w"])
            legacy[group]["actor_w"] = np.concatenate((actor[:, :400], actor[:, STATE_SIZE:STATE_SIZE + 256]), axis=1).tolist()
            legacy[group]["value_w"] = np.asarray(legacy[group]["value_w"])[:, :400].tolist()
        upgraded = ActorCritic.upgrade_legacy_context(legacy)
        for group in ("params", "first", "second"):
            actor = getattr(upgraded, group)["actor_w"]
            np.testing.assert_array_equal(actor[:, 400:STATE_SIZE], 0)
            np.testing.assert_array_equal(actor[:, STATE_SIZE + 256:], 0)
            np.testing.assert_array_equal(np.concatenate((actor[:, :400], actor[:, STATE_SIZE:STATE_SIZE + 256]), axis=1), legacy[group]["actor_w"])
            value_w = getattr(upgraded, group)["value_w"]
            np.testing.assert_array_equal(value_w[:, 400:STATE_SIZE], 0)
            np.testing.assert_array_equal(value_w[:, :400], legacy[group]["value_w"])
        self.assertEqual(upgraded.rng.bit_generator.state, legacy["rng"])
        observed = frame(); before, value, _ = upgraded.evaluate(observed)
        observed["state"][400:] = [0.42] * (STATE_SIZE - 400)
        for option in observed["options"]:
            option["features"][256:] = [0.42] * (ACTION_SIZE - 256)
        after, next_value, _ = upgraded.evaluate(observed)
        np.testing.assert_array_equal(before, after); self.assertEqual(value, next_value)

    def test_v4_task_upgrade_preserves_every_old_tensor_rng_and_zeroes_new_influence(self):
        import copy
        model = ActorCritic(31, hidden=4)
        model.fit([row(model)])
        legacy = copy.deepcopy(model.optimizer_state())
        legacy["contract"] = "minecraft-primitive-candidates/v4"
        for group in ("params", "first", "second"):
            actor = np.asarray(legacy[group]["actor_w"])
            legacy[group]["actor_w"] = np.concatenate((actor[:, :208], actor[:, STATE_SIZE:STATE_SIZE + 256]), axis=1).tolist()
            legacy[group]["value_w"] = np.asarray(legacy[group]["value_w"])[:, :208].tolist()
        upgraded = ActorCritic.upgrade_legacy_context(legacy)
        observed = frame(); before, value, _ = upgraded.evaluate(observed)
        observed["state"][208:] = np.linspace(-1, 1, STATE_SIZE - 208).tolist()
        for option in observed["options"]:
            option["features"][256:] = np.linspace(-1, 1, ACTION_SIZE - 256).tolist()
        after, after_value, _ = upgraded.evaluate(observed)
        np.testing.assert_array_equal(before, after)
        self.assertEqual(value, after_value)
        for group in ("params", "first", "second"):
            for key, old in legacy[group].items():
                actual = getattr(upgraded, group)[key]
                if key == "actor_w":
                    np.testing.assert_array_equal(actual[:, 208:STATE_SIZE], 0)
                    np.testing.assert_array_equal(actual[:, STATE_SIZE + 256:], 0)
                    actual = np.concatenate((actual[:, :208], actual[:, STATE_SIZE:STATE_SIZE + 256]), axis=1)
                elif key == "value_w":
                    np.testing.assert_array_equal(actual[:, 208:STATE_SIZE], 0)
                    actual = actual[:, :208]
                np.testing.assert_array_equal(actual, old)
        self.assertEqual(upgraded.rng.bit_generator.state, legacy["rng"])
        self.assertEqual((upgraded.version, upgraded.updates), (legacy["version"], legacy["updates"]))

    def test_v5_directional_upgrade_preserves_goal_columns_and_zeroes_new_influence(self):
        import copy
        model = ActorCritic(31, hidden=4); model.fit([row(model)])
        legacy = copy.deepcopy(model.optimizer_state()); legacy["contract"] = "minecraft-primitive-candidates/v5"
        for group in ("params", "first", "second"):
            actor = np.asarray(legacy[group]["actor_w"])
            legacy[group]["actor_w"] = np.concatenate((actor[:, :240], actor[:, STATE_SIZE:STATE_SIZE + 256]), axis=1).tolist()
            legacy[group]["value_w"] = np.asarray(legacy[group]["value_w"])[:, :240].tolist()
        upgraded = ActorCritic.upgrade_legacy_context(legacy)
        observed = frame(); before, value, _ = upgraded.evaluate(observed)
        observed["state"][240:] = np.linspace(-1, 1, STATE_SIZE - 240).tolist()
        for option in observed["options"]:
            option["features"][256:] = np.linspace(-1, 1, ACTION_SIZE - 256).tolist()
        after, after_value, _ = upgraded.evaluate(observed)
        np.testing.assert_array_equal(before, after); self.assertEqual(value, after_value)
        for group in ("params", "first", "second"):
            for key, old in legacy[group].items():
                actual = getattr(upgraded, group)[key]
                if key == "actor_w":
                    np.testing.assert_array_equal(actual[:, 240:STATE_SIZE], 0)
                    np.testing.assert_array_equal(actual[:, STATE_SIZE + 256:], 0)
                    actual = np.concatenate((actual[:, :240], actual[:, STATE_SIZE:STATE_SIZE + 256]), axis=1)
                elif key == "value_w":
                    np.testing.assert_array_equal(actual[:, 240:STATE_SIZE], 0)
                    actual = actual[:, :240]
                np.testing.assert_array_equal(actual, old)
        self.assertEqual(upgraded.rng.bit_generator.state, legacy["rng"])
        self.assertEqual((upgraded.version, upgraded.updates), (legacy["version"], legacy["updates"]))

    def test_reward_evidence_uses_admission_state_and_rejects_tampered_reward(self):
        sample = row(ActorCritic(7, hidden=4), session=str(uuid.uuid4()), reward=-0.0995, terminal=False)
        sample.pop("nextValue")
        sample.update(rewardContract="survival-progress/v2", rewardEvidence={
            "schema": "actual-reward-evidence/v1", "healthBefore": 12, "healthAfter": 10,
            "foodBefore": 20, "foodAfter": 19, "saturationBefore": 0, "saturationAfter": 0,
            "airBefore": 300, "airAfter": 299, "discoveries": 0, "objectiveReward": 0})
        validate_transition(sample)
        with self.assertRaises(ValueError):
            validate_transition(dict(sample, reward=0))
        with self.assertRaises(ValueError):
            validate_transition(dict(sample, rewardEvidence=dict(sample["rewardEvidence"], discoveries=True)))
        legacy = {key: value for key, value in sample.items() if key != "rewardEvidence"}
        validate_transition(legacy)

    def test_task_completion_requires_matching_endpoints_and_separate_reward(self):
        import copy
        sample = row(ActorCritic(7, hidden=4), session=str(uuid.uuid4()), reward=1.0005, terminal=False)
        sample.pop("nextValue")
        world = sample["nextWorld"]; identity = str(uuid.uuid4())
        objective = {"id": identity, "world": world, "goal": {"item": "OAK_LOG", "count": 4, "metadata": {}}, "startedTick": 1}
        sample["frame"]["taskMemory"] = {"objective": objective, "owned": 0}
        sample.update(rewardContract="survival-progress/v3", nextTaskMemory={"objective": objective,
            "worldMatches": True, "owned": 4, "currentlyAchieved": True},
            taskCompletion={"objectiveId": identity, "world": world, "item": "OAK_LOG", "required": 4,
                            "beforeOwned": 3, "afterOwned": 4}, rewardEvidence={
            "schema": "actual-reward-evidence/v1", "healthBefore": 20, "healthAfter": 20,
            "foodBefore": 20, "foodAfter": 20, "saturationBefore": 0, "saturationAfter": 0,
            "airBefore": 300, "airAfter": 300, "discoveries": 0, "objectiveReward": 0})
        validate_transition(sample)
        for field, value in (("beforeOwned", 4), ("afterOwned", 3), ("objectiveId", str(uuid.uuid4()))):
            invalid = copy.deepcopy(sample); invalid["taskCompletion"][field] = value
            with self.assertRaises(ValueError):
                validate_transition(invalid)
        with self.assertRaises(ValueError):
            validate_transition(dict(sample, reward=0.0005))
        with self.assertRaises(ValueError):
            validate_transition(dict(sample, rewardContract="survival-progress/v2"))

    def test_recipe_preparation_uses_actual_stock_and_keeps_completion_credit(self):
        sample = row(ActorCritic(7, hidden=4), session=str(uuid.uuid4()), terminal=False)
        sample.pop("nextValue")
        objective = {"id": str(uuid.uuid4()), "world": sample["nextWorld"],
                     "goal": {"item": "WOODEN_PICKAXE", "count": 1}, "startedTick": 1}
        sample["frame"]["taskMemory"] = {"objective": objective, "worldMatches": True}
        endpoint = {"recipe": "minecraft:wooden_pickaxe", "required": 5}
        preparation = {"before": dict(endpoint, filled=5), "after": dict(endpoint, filled=3), "achieved": False}
        sample.update(rewardContract="survival-progress/v4", nextTaskMemory=dict(
            objective=objective, worldMatches=True, currentlyAchieved=False), rewardEvidence={
            "schema": "actual-reward-evidence/v1", "healthBefore": 20, "healthAfter": 20,
            "foodBefore": 20, "foodAfter": 20, "saturationBefore": 0, "saturationAfter": 0,
            "airBefore": 300, "airAfter": 300, "discoveries": 0, "objectiveReward": 0,
            "recipePreparation": preparation})
        discount = 0.99997 ** sample["elapsedTicks"]
        sample["reward"] = sample["elapsedTicks"] * 0.0001 + 0.5 * (discount * 0.6 - 1)
        validate_transition(sample)
        with self.assertRaisesRegex(ValueError, "reward evidence mismatch"):
            validate_transition(dict(sample, reward=0))
        preparation["achieved"] = True
        with self.assertRaisesRegex(ValueError, "preparation endpoints"):
            validate_transition(sample)
        sample["nextTaskMemory"].update(currentlyAchieved=True, owned=1)
        sample["taskCompletion"] = dict(objectiveId=objective["id"], world=sample["nextWorld"],
                                        item="WOODEN_PICKAXE", required=1, beforeOwned=0, afterOwned=1)
        sample["reward"] = sample["elapsedTicks"] * 0.0001 + 0.5 * (discount - 1) + 1
        validate_transition(sample)
        sample["rewardContract"] = "survival-progress/v5"
        preparation["before"].update(filled=13, required=13)
        preparation["after"].update(filled=4, required=13)
        validate_transition(sample)
        with self.assertRaisesRegex(ValueError, "observed recipe stock"):
            validate_transition(dict(sample, rewardContract="survival-progress/v4"))

    def test_focused_recipe_progress_v6_validates_scaled_reward_and_rejects_v5_reward(self):
        model = ActorCritic(7, hidden=4)
        sample = row(model, session=str(uuid.uuid4()), terminal=False)
        sample.pop("nextValue")
        objective = {"id": str(uuid.uuid4()), "world": sample["nextWorld"],
                     "goal": {"item": "WOODEN_PICKAXE", "count": 1}, "startedTick": 1}
        sample["frame"]["taskMemory"] = {"objective": objective, "worldMatches": True}
        endpoint = {"recipe": "minecraft:wooden_pickaxe", "required": 9}
        preparation = {"before": dict(endpoint, filled=9), "after": dict(endpoint, filled=6), "achieved": False}
        sample.update(
            rewardContract="survival-progress/v6",
            objectiveEvents=[],
            nextTaskMemory=dict(objective=objective, worldMatches=True, currentlyAchieved=False),
            rewardEvidence={
                "schema": "actual-reward-evidence/v1", "healthBefore": 20, "healthAfter": 20,
                "foodBefore": 20, "foodAfter": 20, "saturationBefore": 0, "saturationAfter": 0,
                "airBefore": 300, "airAfter": 300, "discoveries": 1, "objectiveReward": 0,
                "recipePreparation": preparation,
            },
        )
        discount = 0.99997 ** sample["elapsedTicks"]
        sample["reward"] = sample["elapsedTicks"] * 0.00001 + 0.02 + 2.0 * (discount * (6 / 9) - 1.0)
        validate_transition(sample)
        v5_reward = sample["elapsedTicks"] * 0.0001 + 0.2 + 0.5 * (discount * (6 / 9) - 1.0)
        with self.assertRaisesRegex(ValueError, "reward evidence mismatch"):
            validate_transition(dict(sample, reward=v5_reward))

        preparation["achieved"] = True
        sample["nextTaskMemory"].update(currentlyAchieved=True, owned=1)
        sample["objectiveEvents"] = ["advancement:minecraft:story/root"]
        sample["rewardEvidence"]["objectiveReward"] = 1
        sample["taskCompletion"] = dict(
            objectiveId=objective["id"], world=sample["nextWorld"],
            item="WOODEN_PICKAXE", required=1, beforeOwned=0, afterOwned=1,
        )
        sample["reward"] = sample["elapsedTicks"] * 0.00001 + 0.02 + 3.0 + 2.0 * (discount - 1.0) + 4.0
        validate_transition(sample)

    def test_goal_driven_recipe_progress_v7_drops_discovery_bonus_and_keeps_v6(self):
        model = ActorCritic(7, hidden=4)
        sample = row(model, session=str(uuid.uuid4()), terminal=False)
        sample.pop("nextValue")
        objective = {"id": str(uuid.uuid4()), "world": sample["nextWorld"],
                     "goal": {"item": "WOODEN_PICKAXE", "count": 1}, "startedTick": 1}
        sample["frame"]["taskMemory"] = {"objective": objective, "worldMatches": True}
        endpoint = {"recipe": "minecraft:wooden_pickaxe", "required": 9}
        preparation = {"before": dict(endpoint, filled=9), "after": dict(endpoint, filled=9), "achieved": False}
        sample.update(
            rewardContract="survival-progress/v7",
            objectiveEvents=[],
            nextTaskMemory=dict(objective=objective, worldMatches=True, currentlyAchieved=False),
            rewardEvidence={
                "schema": "actual-reward-evidence/v1", "healthBefore": 20, "healthAfter": 20,
                "foodBefore": 20, "foodAfter": 20, "saturationBefore": 0, "saturationAfter": 0,
                "airBefore": 300, "airAfter": 300, "discoveries": 1, "objectiveReward": 0,
                "recipePreparation": preparation,
            },
        )
        discount = 0.99997 ** sample["elapsedTicks"]
        sample["reward"] = sample["elapsedTicks"] * 0.00001 + 2.0 * (discount - 1.0)
        validate_transition(sample)
        v6_reward = sample["reward"] + 0.02
        with self.assertRaisesRegex(ValueError, "reward evidence mismatch"):
            validate_transition(dict(sample, reward=v6_reward))

        preparation["achieved"] = True
        sample["nextTaskMemory"].update(currentlyAchieved=True, owned=1)
        sample["objectiveEvents"] = ["advancement:minecraft:story/root"]
        sample["rewardEvidence"]["objectiveReward"] = 1
        sample["taskCompletion"] = dict(
            objectiveId=objective["id"], world=sample["nextWorld"],
            item="WOODEN_PICKAXE", required=1, beforeOwned=0, afterOwned=1,
        )
        sample["reward"] = sample["elapsedTicks"] * 0.00001 + 3.0 + 2.0 * (discount - 1.0) + 4.0
        validate_transition(sample)

    def test_reward_versions_preserve_legacy_rows_and_validate_actual_progress_event_metadata(self):
        model = ActorCritic(7, hidden=4)
        sample = row(model, session=str(uuid.uuid4()))
        sample.pop("nextValue")
        validate_transition(sample)
        sample.update(rewardContract="survival-progress/v2", objectiveEvents=["advancement:minecraft:story/mine_stone"])
        validate_transition(sample)
        for invalid in (dict(sample, rewardContract="survival-progress/v1"),
                        dict(sample, objectiveEvents=["Teacher says mine"]),
                        dict(sample, objectiveEvents=sample["objectiveEvents"] * 2)):
            with self.assertRaises(ValueError):
                validate_transition(invalid)

    def test_checkpoint_report_counts_only_the_reward_contracts_of_its_accepted_rollout(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "run"
            trainer = PaperTrainer(output, seed=7, rollout_size=32, maximum=32, max_bytes=4*1024*1024)
            session = str(uuid.uuid4())
            try:
                for start in (0, 16):
                    rows = []
                    for sequence in range(start, start+16):
                        sample = row(trainer.model, session=session, sequence=sequence)
                        sample.pop("nextValue")
                        sample.update(rewardContract="survival-progress/v2", objectiveEvents=[])
                        rows.append(sample)
                    trainer.handle({"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                                    "frame": frame(), "transitions": rows})
            finally:
                trainer.close()
            report = json.loads((output / "training-report.json").read_bytes())
            self.assertEqual(report["rewardContracts"], {"survival-progress/v2": 32})

    def test_explicit_context_upgrade_preserves_legacy_actor_critic_and_adam_without_inventing_learning(self):
        model = ActorCritic(7, hidden=4)
        model.fit([row(model)], epochs=1)
        legacy = model.optimizer_state()
        legacy["contract"] = "minecraft-primitive-candidates/v1"
        for group in ("params", "first", "second"):
            actor = np.asarray(legacy[group]["actor_w"])
            legacy[group]["actor_w"] = np.concatenate((actor[:, :64], actor[:, STATE_SIZE:STATE_SIZE + 256]), axis=1).tolist()
            legacy[group]["value_w"] = np.asarray(legacy[group]["value_w"])[:, :64].tolist()
        with self.assertRaises(ValueError):
            ActorCritic.from_optimizer_state(legacy)
        upgraded = ActorCritic.upgrade_legacy_context(legacy)
        observed = frame()
        observed["state"] = np.linspace(-0.3, 0.3, STATE_SIZE).tolist()
        for option in observed["options"]:
            option["features"][256:] = np.linspace(-0.5, 0.5, ACTION_SIZE - 256).tolist()
        old_state = np.asarray(observed["state"][:64])
        old_x = np.concatenate((np.tile(old_state, (2, 1)), [option["features"][:256] for option in observed["options"]]), axis=1)
        p = {key: np.asarray(value) for key, value in legacy["params"].items()}
        logits = np.tanh(old_x @ p["actor_w"].T + p["actor_b"]) @ p["actor_out"] + p["actor_bias"][0]
        expected = np.exp(logits-logits.max()); expected /= expected.sum()
        expected_value = np.tanh(p["value_w"] @ old_state + p["value_b"]) @ p["value_out"] + p["value_bias"][0]
        probabilities, value, _ = upgraded.evaluate(observed)
        np.testing.assert_allclose(expected, probabilities, atol=1e-14)
        self.assertAlmostEqual(float(expected_value), value, places=12)
        for group in ("params", "first", "second"):
            np.testing.assert_array_equal(getattr(upgraded, group)["actor_w"][:, 64:STATE_SIZE], 0)
            np.testing.assert_array_equal(getattr(upgraded, group)["actor_w"][:, STATE_SIZE + 256:], 0)
            np.testing.assert_array_equal(getattr(upgraded, group)["value_w"][:, 64:STATE_SIZE], 0)
        self.assertEqual(upgraded.version, model.version)
        self.assertEqual(upgraded.updates, model.updates)
        self.assertEqual(upgraded.rng.bit_generator.state, model.rng.bit_generator.state)

    def test_v2_upgrade_preserves_all_old_columns_and_moments_with_zero_new_gear_influence(self):
        model = ActorCritic(11, hidden=4)
        model.fit([row(model)], epochs=1)
        legacy = model.optimizer_state()
        legacy["contract"] = "minecraft-primitive-candidates/v2"
        for group in ("params", "first", "second"):
            actor = np.asarray(legacy[group]["actor_w"])
            legacy[group]["actor_w"] = np.concatenate((actor[:, :128], actor[:, STATE_SIZE:STATE_SIZE + 256]), axis=1).tolist()
            legacy[group]["value_w"] = np.asarray(legacy[group]["value_w"])[:, :128].tolist()
        with self.assertRaises(ValueError):
            ActorCritic.from_optimizer_state(legacy)
        upgraded = ActorCritic.upgrade_legacy_context(legacy)
        observed = frame()
        base_probabilities, base_value, _ = upgraded.evaluate(observed)
        observed["state"][128:] = np.linspace(-1, 1, STATE_SIZE - 128).tolist()
        for option in observed["options"]:
            option["features"][256:] = np.linspace(-1, 1, ACTION_SIZE - 256).tolist()
        probabilities, value, _ = upgraded.evaluate(observed)
        np.testing.assert_array_equal(base_probabilities, probabilities)
        self.assertEqual(base_value, value)
        for group in ("params", "first", "second"):
            for key, old in legacy[group].items():
                actual = getattr(upgraded, group)[key]
                if key == "actor_w":
                    np.testing.assert_array_equal(actual[:, 128:STATE_SIZE], 0)
                    np.testing.assert_array_equal(actual[:, STATE_SIZE + 256:], 0)
                    actual = np.concatenate((actual[:, :128], actual[:, STATE_SIZE:STATE_SIZE + 256]), axis=1)
                elif key == "value_w":
                    np.testing.assert_array_equal(actual[:, 128:STATE_SIZE], 0)
                    actual = actual[:, :128]
                np.testing.assert_array_equal(actual, old)
        self.assertEqual(upgraded.rng.bit_generator.state, legacy["rng"])
        self.assertEqual((upgraded.version, upgraded.updates), (legacy["version"], legacy["updates"]))

    def test_v3_motion_upgrade_keeps_predictions_counters_rng_and_adam_before_any_new_learning(self):
        model = ActorCritic(17, hidden=4)
        model.fit([row(model)], epochs=1)
        legacy = model.optimizer_state()
        legacy["contract"] = "minecraft-primitive-candidates/v3"
        for group in ("params", "first", "second"):
            actor = np.asarray(legacy[group]["actor_w"])
            legacy[group]["actor_w"] = np.concatenate((actor[:, :192], actor[:, STATE_SIZE:STATE_SIZE + 256]), axis=1).tolist()
            legacy[group]["value_w"] = np.asarray(legacy[group]["value_w"])[:, :192].tolist()
        with self.assertRaises(ValueError):
            ActorCritic.from_optimizer_state(legacy)
        upgraded = ActorCritic.upgrade_legacy_context(legacy)
        observed = frame()
        probabilities, value, _ = upgraded.evaluate(observed)
        observed["state"][192:] = np.linspace(-1, 1, STATE_SIZE - 192).tolist()
        for option in observed["options"]:
            option["features"][256:] = np.linspace(-1, 1, ACTION_SIZE - 256).tolist()
        after, after_value, _ = upgraded.evaluate(observed)
        np.testing.assert_array_equal(probabilities, after)
        self.assertEqual(value, after_value)
        for group in ("params", "first", "second"):
            for key, old in legacy[group].items():
                actual = getattr(upgraded, group)[key]
                if key == "actor_w":
                    np.testing.assert_array_equal(actual[:, 192:STATE_SIZE], 0)
                    np.testing.assert_array_equal(actual[:, STATE_SIZE + 256:], 0)
                    actual = np.concatenate((actual[:, :192], actual[:, STATE_SIZE:STATE_SIZE + 256]), axis=1)
                elif key == "value_w":
                    np.testing.assert_array_equal(actual[:, 192:STATE_SIZE], 0)
                    actual = actual[:, :192]
                np.testing.assert_array_equal(actual, old)
        self.assertEqual(upgraded.rng.bit_generator.state, legacy["rng"])
        self.assertEqual((upgraded.version, upgraded.updates), (legacy["version"], legacy["updates"]))

    def test_checkpoint_retains_critic_adam_counters_and_random_stream_for_exact_continuation(self):
        model = ActorCritic(7, hidden=4)
        model.fit([row(model)], epochs=1)
        restored = ActorCritic.from_optimizer_state(model.optimizer_state())
        for group in ("params", "first", "second"):
            for key, value in getattr(model, group).items():
                np.testing.assert_array_equal(value, getattr(restored, group)[key])
        self.assertEqual(model.sample(frame()), restored.sample(frame()))
        following = [row(model)]
        model.fit(following, epochs=1)
        restored.fit(following, epochs=1)
        self.assertEqual(model.version, restored.version)
        self.assertEqual(model.updates, restored.updates)
        for key, value in model.params.items():
            np.testing.assert_array_equal(value, restored.params[key])
        broken = model.optimizer_state()
        broken["second"]["actor_bias"][0] = -1
        with self.assertRaises(ValueError):
            ActorCritic.from_optimizer_state(broken)

    def test_actor_and_value_gradients_match_numerical_derivatives(self):
        model = ActorCritic(7, hidden=4)
        rows = [row(model)]
        advantages, returns = np.array([0.7]), np.array([0.8])
        gradient, _ = model.gradients(rows, advantages, returns)
        for key, index in (("actor_w", (0, STATE_SIZE)), ("actor_b", (0,)), ("actor_out", (0,)),
                           ("value_w", (0, 0)), ("value_out", (0,))):
            original = model.params[key][index]
            epsilon = 1e-6
            model.params[key][index] = original + epsilon
            _, plus = model.gradients(rows, advantages, returns)
            model.params[key][index] = original - epsilon
            _, minus = model.gradients(rows, advantages, returns)
            model.params[key][index] = original
            numerical = (plus["loss"] - minus["loss"]) / (2 * epsilon)
            self.assertAlmostEqual(numerical, gradient[key][index], places=5)

    def test_positive_advantage_can_change_the_action_probability_without_teacher_labels(self):
        model = ActorCritic(7, hidden=4)
        before = model.evaluate(frame())[0][1]
        model.fit([row(model)], epochs=4, minibatch=1)
        self.assertGreater(model.evaluate(frame())[0][1], before)
        self.assertEqual(model.version, 1)
        self.assertGreater(model.updates, 0)

    def test_initialization_and_old_behavior_versions_are_not_accepted_as_new_training(self):
        model = ActorCritic(7, hidden=4)
        with self.assertRaises(ValueError):
            model.artifact(environment_steps=1, training_run="fixture", rollout_sha256="0" * 64)
        old = row(model)
        model.fit([old], epochs=1)
        with self.assertRaises(ValueError):
            model.fit([old], epochs=1)

    def test_interleaved_environments_and_gaps_do_not_leak_advantages(self):
        base = {"value": 0, "nextValue": 0, "elapsedTicks": 1, "terminated": False, "truncated": False,
                "frame": {"world": "first-world"}, "nextWorld": "first-world", "appliedTick": 10}
        rows = [{**base, "session": "a", "sequence": 0, "reward": 1},
                {**base, "session": "b", "sequence": 0, "reward": 10},
                {**base, "session": "a", "sequence": 1, "reward": 2, "appliedTick": 11, "terminated": True}]
        advantages, _ = generalized_advantages(rows, gamma_tick=1, gae_lambda=0.95)
        np.testing.assert_allclose(advantages, [2.9, 10, 2])
        rows[-1]["sequence"] = 2
        advantages, _ = generalized_advantages(rows, gamma_tick=1, gae_lambda=0.95)
        np.testing.assert_allclose(advantages, [1, 10, 2])
        rows[-1]["sequence"], rows[-1]["appliedTick"] = 1, 15
        advantages, _ = generalized_advantages(rows, gamma_tick=1, gae_lambda=0.95)
        np.testing.assert_allclose(advantages, [1, 10, 2])
        rows[-1]["appliedTick"], rows[-1]["frame"] = 11, {"world": "other-world"}
        advantages, _ = generalized_advantages(rows, gamma_tick=1, gae_lambda=0.95)
        np.testing.assert_allclose(advantages, [1, 10, 2])

    def test_invalid_or_future_label_features_are_not_actor_inputs(self):
        observed = frame()
        state, x = inputs(observed)
        self.assertEqual(x.shape, (2, STATE_SIZE + ACTION_SIZE))
        observed["options"][1]["id"] = "left"
        with self.assertRaises(ValueError):
            inputs(observed)
        observed = frame()
        observed["state"][0] = float("nan")
        with self.assertRaises(ValueError):
            inputs(observed)

    def test_applied_state_and_value_eliminate_request_lag_td_drift(self):
        model = ActorCritic(7, hidden=4)
        sample = row(model, session="s1", sequence=0, reward=0.0, terminal=False)
        sample["appliedState"] = list(sample["frame"]["state"])
        sample["appliedState"][135] = 0.9
        sample["frame"]["state"][135] = 0.2  # Stale request state differs from admission state
        sample["nextState"] = list(sample["appliedState"])
        applied_v, _ = model.value(sample["appliedState"])
        sample["appliedValue"] = applied_v
        sample["nextValue"] = applied_v
        sample["value"] = applied_v - 0.75  # Stale request value would otherwise inject +0.75 TD phantom profit

        advantages, returns = generalized_advantages([sample], gamma_tick=1.0, gae_lambda=0.95)
        self.assertAlmostEqual(float(advantages[0]), 0.0, places=12)
        self.assertAlmostEqual(float(returns[0]), applied_v, places=12)

        adv = np.array([0.7]); target = np.array([applied_v + 1.0])
        grad, _ = model.gradients([sample], adv, target)
        for key, idx in (("actor_w", (0, STATE_SIZE)), ("value_w", (0, 135)), ("value_out", (0,))):
            original = model.params[key][idx]
            eps = 1e-6
            model.params[key][idx] = original + eps
            _, plus = model.gradients([sample], adv, target)
            model.params[key][idx] = original - eps
            _, minus = model.gradients([sample], adv, target)
            model.params[key][idx] = original
            self.assertAlmostEqual((plus["loss"] - minus["loss"]) / (2 * eps), grad[key][idx], places=5)


class BridgeProtocolTests(unittest.TestCase):
    def test_learning_controls_preserve_the_same_weights_identity_and_durable_disabled_state(self):
        with tempfile.TemporaryDirectory() as temporary:
            trainer = PaperTrainer(Path(temporary) / "run", seed=7, rollout_size=32, maximum=32, max_bytes=16*1024*1024)
            def control(enabled, brain=None):
                return trainer.control({"schema": CONTROL_SCHEMA, "id": str(uuid.uuid4()), "operation": "learning",
                                        "brainId": brain or trainer.brain_id, "enabled": enabled})
            try:
                off = control(False)
                self.assertFalse(off["status"]["metadata"]["learningEnabled"])
                self.assertFalse(brain_session.load(trainer.output / "runtime-session.json")["learningEnabled"])
                trainer.handle({"schema": SCHEMA, "id": str(uuid.uuid4()), "environment": "minecraft-paper", "frame": frame()})
                on = control(True)
                self.assertTrue(on["status"]["metadata"]["learningEnabled"])
                self.assertEqual(off["weightsSha256"], on["weightsSha256"])
                self.assertEqual(off["status"]["metadata"]["brainId"], on["status"]["metadata"]["brainId"])
                self.assertEqual(trainer.model.updates, 0)
                with self.assertRaises(ValueError):
                    control(False, str(uuid.uuid4()))
                self.assertTrue(trainer.learning_enabled)
            finally:
                trainer.close()

    def test_session_restores_the_same_fresh_brain_rng_and_disabled_learning_without_world_data(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            trainer = PaperTrainer(root / "first", seed=7, rollout_size=32, maximum=32, max_bytes=16*1024*1024,
                                   learning_enabled=False)
            trainer.model.sample(frame())
            trainer.close()
            session_file = trainer.output / "runtime-session.json"
            session = brain_session.load(session_file)
            restored = PaperTrainer(root / "second", seed=99, rollout_size=32, maximum=32, max_bytes=16*1024*1024,
                                    resume=Path(session["checkpoint"]), learning_enabled=session["learningEnabled"], restore_state=session)
            try:
                self.assertEqual(restored.brain_id, trainer.brain_id)
                self.assertFalse(restored.learning_enabled)
                self.assertEqual(trainer.model.sample(frame()), restored.model.sample(frame()))
                self.assertNotIn("inventory", session)
                self.assertNotIn("position", session)
            finally:
                restored.close()
            corrupted = json.loads(session_file.read_text())
            corrupted["payload"]["learningEnabled"] = True
            session_file.write_text(json.dumps(corrupted))
            with self.assertRaisesRegex(ValueError, "integrity mismatch"):
                brain_session.load(session_file)

    def test_fresh_initial_state_can_resume_without_becoming_a_pretrained_model(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            trainer = PaperTrainer(base / "fresh", seed=7, rollout_size=32, maximum=32, max_bytes=16*1024*1024,
                                   learning_enabled=False, start_state="fresh")
            original = {key: value.copy() for key, value in trainer.model.params.items()}
            identity = trainer.brain_id
            trainer.close()
            self.assertFalse((base / "fresh/minecraft-rl-actor.json").exists())
            with self.assertRaises(ValueError):
                resume_checkpoint(base / "fresh")
            resumed = PaperTrainer(base / "resume", seed=99, rollout_size=32, maximum=32, max_bytes=16*1024*1024,
                                   resume=base / "fresh", learning_enabled=False)
            try:
                self.assertEqual(resumed.brain_id, identity)
                self.assertEqual(resumed.model.updates, 0)
                for key, value in original.items():
                    np.testing.assert_array_equal(value, resumed.model.params[key])
            finally:
                resumed.close()
            with self.assertRaises(ValueError):
                PaperTrainer(base / "pretrained", seed=99, rollout_size=32, maximum=32, max_bytes=16*1024*1024,
                             resume=base / "fresh", start_state="pretrained", learning_enabled=False)

    def test_fresh_inference_uses_the_same_network_without_updates_or_retroactive_learning(self):
        with tempfile.TemporaryDirectory() as temporary:
            trainer = PaperTrainer(Path(temporary) / "run", seed=7, rollout_size=32, maximum=32,
                                   max_bytes=16*1024*1024, start_state="fresh", learning_enabled=False)
            before = {key: value.copy() for key, value in trainer.model.params.items()}
            try:
                transition = row(trainer.model, session=str(uuid.uuid4()))
                transition.pop("nextValue")
                packet = {"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                          "frame": frame(), "transitions": [transition]}
                response = trainer.handle(packet)
                self.assertFalse(response["metadata"]["learningEnabled"])
                self.assertEqual(response["metadata"]["trainingStartState"], "fresh")
                self.assertEqual(trainer.steps, 0)
                self.assertEqual(trainer.model.updates, 0)
                self.assertEqual(trainer.model.version, 0)
                for key, value in before.items():
                    np.testing.assert_array_equal(value, trainer.model.params[key])
                self.assertEqual(trainer.discard_reasons, {"learning-disabled": 1})
                # Even if a later authorized controller enables learning, this old off-period row stays excluded.
                trainer.learning_enabled = True
                packet["id"] = str(uuid.uuid4())
                trainer.handle(packet)
                self.assertEqual(trainer.steps, 0)
                self.assertFalse((trainer.output / "minecraft-rl-actor.json").exists())
            finally:
                trainer.close()

    def test_checkpoint_permission_failure_is_not_hidden_as_a_transport_retry(self):
        with tempfile.TemporaryDirectory() as temporary:
            channel = Path(temporary)
            request = channel / (str(uuid.uuid4()) + ".request.json")
            request.write_text(json.dumps({"id": request.stem.removesuffix(".request")}))
            trainer = mock.Mock()
            trainer.handle.side_effect = PermissionError("checkpoint storage fixture")
            with self.assertRaises(PermissionError):
                exchange_request(request, channel, trainer, {})

    def test_windows_cleanup_lock_retries_the_same_response_without_learning_or_sampling_twice(self):
        with tempfile.TemporaryDirectory() as temporary:
            channel = Path(temporary)
            identity = str(uuid.uuid4())
            request = channel / (identity + ".request.json")
            request.write_text(json.dumps({"id": identity}))
            trainer = mock.Mock()
            trainer.handle.return_value = {"id": identity, "candidate": "fixture"}
            pending = {}
            unlink = Path.unlink
            calls = 0
            def locked(path, *args, **kwargs):
                nonlocal calls
                if path == request and calls == 0:
                    calls += 1
                    raise PermissionError("Windows sharing violation fixture")
                return unlink(path, *args, **kwargs)
            with mock.patch.object(Path, "unlink", locked):
                exchange_request(request, channel, trainer, pending)
                self.assertTrue(request.exists())
                response = (channel / (identity + ".response.json")).read_bytes()
                exchange_request(request, channel, trainer, pending)
            self.assertEqual(trainer.handle.call_count, 1)
            self.assertEqual(response, (channel / (identity + ".response.json")).read_bytes())
            self.assertEqual(pending, {})
            self.assertFalse(request.exists())

    def test_directory_recovery_checks_prior_raw_prefix_and_preserves_an_older_complete_optimizer(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "run"
            trainer = PaperTrainer(output, seed=7, rollout_size=32, maximum=64, max_bytes=16*1024*1024)
            try:
                for batch in range(2):
                    for number in range(32):
                        transition = row(trainer.model, session=str(uuid.UUID(int=5)), sequence=batch*32+number)
                        transition.pop("nextValue")
                        trainer.handle({"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                                        "frame": frame(), "transitions": [transition]})
                    trainer.poll_training(wait=True)
            finally:
                trainer.close()
            pointer = json.loads((output / "latest-checkpoint.json").read_text())
            latest = output / pointer["bundle"]
            (latest / "minecraft-rl-actor.json").write_text("interrupted export fixture")
            model, parent = resume_checkpoint(output)
            self.assertEqual(model.version, 1)
            self.assertEqual(parent["environmentSteps"], 32)
            self.assertIn("checkpoints/version-", parent["recoveredCheckpoint"])
            self.assertTrue(latest.exists())
            older = output / parent["recoveredCheckpoint"]
            (older.parent / "minecraft-rl-actor.json").write_text("also corrupted fixture")
            with self.assertRaises(ValueError):
                resume_checkpoint(output, allow_initial=True)

    def test_rollout_drains_in_flight_goal_completion_before_changing_policy(self):
        with tempfile.TemporaryDirectory() as temporary:
            trainer = PaperTrainer(Path(temporary) / "run", seed=7, rollout_size=32, maximum=64, max_bytes=16*1024*1024)
            session = str(uuid.uuid4())
            release_update = threading.Event()

            def request(*transitions, active=False):
                return trainer.handle({"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                                       "frame": frame(), "transitions": list(transitions), "activeDecision": active})

            def fit_after_release(model, transitions):
                release_update.wait()
                return PaperTrainer.fit_snapshot(model, transitions)

            try:
                with mock.patch.object(trainer, "fit_snapshot", side_effect=fit_after_release):
                    rows = [row(trainer.model, session=session, sequence=i, terminal=False) for i in range(32)]
                    for transition in rows:
                        transition.pop("nextValue")
                    request(*rows[:16]); request(*rows[16:30])
                    drain = request(rows[30], active=True)
                    self.assertTrue(drain["rolloutBoundary"])
                    self.assertIsNone(trainer.training)
                    self.assertEqual(trainer.steps, 31)

                    completion = rows[31]
                    world, identity = completion["nextWorld"], str(uuid.uuid4())
                    objective = {"id": identity, "world": world,
                                 "goal": {"item": "CRAFTING_TABLE", "count": 1, "metadata": {}}, "startedTick": 1}
                    completion["frame"]["taskMemory"] = {"objective": objective, "owned": 0}
                    completion.update(reward=1.0005, rewardContract="survival-progress/v3",
                        nextTaskMemory={"objective": objective, "worldMatches": True, "owned": 1, "currentlyAchieved": True},
                        taskCompletion={"objectiveId": identity, "world": world, "item": "CRAFTING_TABLE",
                                        "required": 1, "beforeOwned": 0, "afterOwned": 1},
                        rewardEvidence={"schema": "actual-reward-evidence/v1", "healthBefore": 20, "healthAfter": 20,
                                        "foodBefore": 20, "foodAfter": 20, "saturationBefore": 0, "saturationAfter": 0,
                                        "airBefore": 300, "airAfter": 300, "discoveries": 0, "objectiveReward": 0})
                    self.assertTrue(request(completion)["rolloutBoundary"])
                    self.assertTrue(request(completion)["rolloutBoundary"])
                    self.assertEqual(trainer.steps, 32)
                    release_update.set()
                    trainer.poll_training(wait=True)
                    self.assertEqual(request()["modelVersion"], 1)
                    self.assertEqual(trainer.discard_reasons, {})
                    accepted = [json.loads(line) for line in (trainer.output / "paper-rollouts.jsonl").read_text().splitlines()]
                    self.assertEqual(len(accepted), 32)
                    self.assertEqual(accepted[-1]["taskCompletion"], completion["taskCompletion"])
                    self.assertEqual(accepted[-1]["reward"], completion["reward"])
            finally:
                release_update.set()
                trainer.close()

    def test_episode_diagnostics_include_excluded_death_and_keep_unfinished_episodes_incomplete(self):
        with tempfile.TemporaryDirectory() as temporary:
            trainer = PaperTrainer(Path(temporary) / "run", seed=7, rollout_size=32, maximum=32, max_bytes=16*1024*1024)
            session = str(uuid.uuid4())
            try:
                first = row(trainer.model, session=session, sequence=0, terminal=False)
                first.pop("nextValue")
                second = row(trainer.model, session=session, sequence=2, reward=-10, terminal=True)
                second.pop("nextValue"); second["modelVersion"] = 1
                trainer.handle({"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                                "frame": frame(), "transitions": [first, second]})
                record = json.loads((trainer.output / "episode-outcomes.jsonl").read_text())
                self.assertEqual(record["observedTransitions"], 2)
                self.assertEqual(record["missingSequenceCount"], 1)
                self.assertEqual(record["actualServerTicks"], 10)
                self.assertEqual(record["rewardSum"], -9)
                self.assertFalse(record["completeObservedSequence"])
                self.assertTrue(record["terminated"])
                self.assertEqual(trainer.steps, 1)
                self.assertEqual(trainer.death_episodes, 1)
                unfinished = row(trainer.model, session=str(uuid.uuid4()), terminal=False)
                unfinished.pop("nextValue")
                trainer.handle({"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                                "frame": frame(), "transitions": [unfinished]})
            finally:
                trainer.close()
            records = [json.loads(line) for line in (trainer.output / "episode-outcomes.jsonl").read_text().splitlines()]
            self.assertEqual(len(records), 2)
            self.assertFalse(records[1]["episodeEnded"])
            self.assertFalse(records[1]["completeObservedSequence"])

    def test_explicit_stop_closes_the_trainer_without_inventing_an_update_from_an_incomplete_buffer(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            trainer = PaperTrainer(base / "run", seed=7, rollout_size=32, maximum=32, max_bytes=16*1024*1024)
            stopped = base / "stop.request"
            stopped.touch()
            serve(base / "channel", trainer, stopped)
            self.assertTrue(trainer.raw.closed)
            self.assertTrue(trainer.discarded_raw.closed)
            self.assertIsNone(trainer.training)
            self.assertFalse((trainer.output / "minecraft-rl-actor.json").exists())

    def test_off_policy_death_is_excluded_from_ppo_but_preserved_with_its_actual_outcome(self):
        with tempfile.TemporaryDirectory() as temporary:
            trainer = PaperTrainer(Path(temporary) / "run", seed=7, rollout_size=32, maximum=32, max_bytes=16*1024*1024)
            try:
                death = row(trainer.model, session=str(uuid.uuid4()), reward=-10, terminal=True)
                death.pop("nextValue")
                death["modelVersion"] = 1
                trainer.handle({"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                                "frame": frame(), "transitions": [death]})
                self.assertEqual(trainer.steps, 0)
                self.assertEqual(trainer.rows, [])
                self.assertEqual(trainer.discarded_off_policy, 1)
                preserved = json.loads((trainer.output / "discarded-transitions.jsonl").read_text())
                self.assertEqual(preserved["transition"], death)
                self.assertEqual(preserved["reason"], "off-policy-version")
                self.assertEqual((trainer.output / "paper-rollouts.jsonl").stat().st_size, 0)
                self.assertFalse((trainer.output / "minecraft-rl-actor.json").exists())
            finally:
                trainer.close()
    def test_resume_checks_raw_prefix_and_checkpoint_identity_without_reusing_previous_rows(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "first"
            trainer = PaperTrainer(output, seed=7, rollout_size=32, maximum=32, max_bytes=16 * 1024 * 1024)
            try:
                for sequence in range(32):
                    transition = row(trainer.model, session=str(uuid.UUID(int=5)), sequence=sequence)
                    transition.pop("nextValue")
                    trainer.handle({"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                                    "frame": frame(), "transitions": [transition]})
                trainer.poll_training(wait=True)
            finally:
                trainer.close()
            checkpoint = output / "optimizer-checkpoint.json"
            continued = PaperTrainer(Path(temporary) / "second", seed=99, rollout_size=32, maximum=32,
                                     max_bytes=16 * 1024 * 1024, resume=checkpoint)
            try:
                self.assertEqual(continued.model.version, 1)
                self.assertEqual(continued.parent_steps, 32)
                self.assertEqual(continued.brain_id, trainer.brain_id)
                status = continued.handle({"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                                           "frame": frame(), "transitions": []})
                self.assertEqual(status["metadata"]["brainId"], trainer.brain_id)
                self.assertEqual(status["metadata"]["trainingStartState"], "resume")
                self.assertEqual(status["environmentSteps"], 32)
                self.assertEqual(status["metadata"]["gradientUpdates"], trainer.model.updates)
                self.assertEqual(continued.steps, 0)
                self.assertEqual(continued.rows, [])
                self.assertFalse((continued.output / "minecraft-rl-actor.json").exists())
                self.assertEqual(continued.parents[-1]["trainingRun"], trainer.run)
            finally:
                continued.close()
            pretrained = PaperTrainer(Path(temporary) / "pretrained-inference", seed=99, rollout_size=32, maximum=32,
                                      max_bytes=16*1024*1024, resume=checkpoint, start_state="pretrained", learning_enabled=False)
            try:
                self.assertEqual(pretrained.brain_id, trainer.brain_id)
                self.assertEqual(pretrained.training_start_state, "pretrained")
                self.assertFalse(pretrained.learning_enabled)
                for key, value in trainer.model.params.items():
                    np.testing.assert_array_equal(value, pretrained.model.params[key])
                self.assertEqual(pretrained.model.updates, trainer.model.updates)
            finally:
                pretrained.close()
            with self.assertRaises((ValueError, KeyError)):
                resume_checkpoint(output / "minecraft-rl-actor.json")
            raw = output / "paper-rollouts.jsonl"
            original = raw.read_bytes()
            raw.write_bytes(b"!" + original[1:])
            with self.assertRaisesRegex(ValueError, "rollout provenance mismatch"):
                resume_checkpoint(checkpoint)
            raw.write_bytes(original)
            corrupted = json.loads(checkpoint.read_text())
            corrupted["environmentSteps"] += 1
            checkpoint.write_text(json.dumps(corrupted))
            with self.assertRaisesRegex(ValueError, "checkpoint provenance mismatch"):
                resume_checkpoint(checkpoint)

    def test_optimizer_wait_does_not_block_sampling_or_mutate_the_active_policy(self):
        with tempfile.TemporaryDirectory() as temporary:
            trainer = PaperTrainer(Path(temporary) / "run", seed=7, rollout_size=32, maximum=32, max_bytes=16 * 1024 * 1024)
            entered, release = threading.Event(), threading.Event()
            original = ActorCritic.fit

            def delayed(model, rows):
                entered.set()
                if not release.wait(5):
                    raise RuntimeError("numerical fixture update not released")
                return original(model, rows, epochs=1)

            try:
                with mock.patch.object(ActorCritic, "fit", delayed):
                    session = str(uuid.uuid4())
                    before = trainer.model.params["actor_w"].copy()
                    for sequence in range(32):
                        transition = row(trainer.model, session=session, sequence=sequence)
                        transition.pop("nextValue")
                        trainer.handle({"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                                        "frame": frame(), "transitions": [transition]})
                    self.assertTrue(entered.wait(2))
                    self.assertIsNotNone(trainer.training)
                    # Sampling remains on a frozen parameter snapshot while the optimizer is deliberately blocked.
                    answer = trainer.handle({"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                                             "frame": frame(), "transitions": []})
                    self.assertEqual(answer["modelVersion"], 0)
                    np.testing.assert_array_equal(before, trainer.model.params["actor_w"])
                    self.assertFalse((trainer.output / "minecraft-rl-actor.json").exists())
                    release.set()
                    trainer.poll_training(wait=True)
                    self.assertEqual(trainer.model.version, 1)
                    # This artifact is a temporary numerical/protocol fixture, never actual Minecraft learning evidence.
                    self.assertTrue((trainer.output / "minecraft-rl-actor.json").exists())
            finally:
                release.set()
                trainer.close()

    def test_no_artifact_is_created_while_waiting_and_wrong_environment_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "run"
            trainer = PaperTrainer(directory, seed=7, rollout_size=32, maximum=32, max_bytes=8 * 1024 * 1024)
            try:
                self.assertFalse((directory / "minecraft-rl-actor.json").exists())
                with self.assertRaises(ValueError):
                    trainer.handle({"schema": SCHEMA, "environment": "toy", "id": str(uuid.uuid4()), "frame": frame()})
                self.assertEqual(trainer.steps, 0)
            finally:
                trainer.close()

    def test_duplicate_acknowledgements_do_not_become_duplicate_game_experience(self):
        with tempfile.TemporaryDirectory() as temporary:
            trainer = PaperTrainer(Path(temporary) / "run", seed=7, rollout_size=32, maximum=32, max_bytes=8 * 1024 * 1024)
            try:
                transition = row(trainer.model, session=str(uuid.uuid4()))
                transition.pop("nextValue")
                packet = {"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()), "frame": frame(), "transitions": [transition]}
                trainer.handle(packet)
                packet["id"] = str(uuid.uuid4())
                trainer.handle(packet)
                self.assertEqual(trainer.steps, 1)  # Protocol fixture, not actual Paper gameplay.
                self.assertFalse((trainer.output / "minecraft-rl-actor.json").exists())
            finally:
                trainer.close()

    def test_parallel_channels_drain_both_worlds_without_off_policy_discards(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            trainer = PaperTrainer(base / "run", seed=7, rollout_size=32, maximum=64, max_bytes=16 * 1024 * 1024)
            session_a, session_b = str(uuid.uuid4()), str(uuid.uuid4())
            release_update = threading.Event()

            def req(channel_id, *transitions, active=False):
                return trainer.handle({"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                                       "frame": frame(), "transitions": list(transitions), "activeDecision": active},
                                      channel_id=channel_id)

            def fit_after_release(model, transitions):
                release_update.wait()
                return PaperTrainer.fit_snapshot(model, transitions)

            try:
                with mock.patch.object(trainer, "fit_snapshot", side_effect=fit_after_release):
                    rows_a = [row(trainer.model, session=session_a, sequence=i, terminal=False) for i in range(16)]
                    rows_b = [row(trainer.model, session=session_b, sequence=i, terminal=False) for i in range(16)]
                    for t in rows_a + rows_b:
                        t.pop("nextValue")
                    self.assertFalse(req("chan-a", *rows_a[:14]).get("rolloutBoundary", False))
                    self.assertFalse(req("chan-b", *rows_b[:14]).get("rolloutBoundary", False))
                    self.assertEqual(trainer.steps, 28)
                    # Both worlds receive a new sample while their first decision remains active.
                    self.assertFalse(req("chan-a", active=True).get("rolloutBoundary", False))
                    self.assertFalse(req("chan-b", active=True).get("rolloutBoundary", False))
                    # A sends its queued closure while B still owes both a queued and an active transition.
                    self.assertTrue(req("chan-a", rows_a[14], active=True)["rolloutBoundary"])
                    self.assertEqual(trainer.steps, 29)
                    self.assertTrue(req("chan-b", rows_b[14], active=True)["rolloutBoundary"])
                    self.assertIsNone(trainer.training)
                    self.assertEqual(trainer.steps, 30)
                    # Channel A delivers slot 31 and becomes idle; channel B still has 1 in-flight -> hold A at boundary.
                    self.assertTrue(req("chan-a", rows_a[15], active=False)["rolloutBoundary"])
                    self.assertEqual(trainer.steps, 31)
                    self.assertIsNone(trainer.training)
                    # Channel B delivers slot 32 -> triggers synchronous PPO update with zero off-policy discards.
                    self.assertTrue(req("chan-b", rows_b[15], active=False)["rolloutBoundary"])
                    self.assertEqual(trainer.steps, 32)
                    self.assertIsNotNone(trainer.training)
                    release_update.set()
                    trainer.poll_training(wait=True)
                    self.assertEqual(req("chan-a", active=False)["modelVersion"], 1)
                    self.assertEqual(req("chan-b", active=False)["modelVersion"], 1)
                    self.assertEqual(trainer.discard_reasons, {})
            finally:
                release_update.set()
                trainer.close()
            # Also verify serve() accepts multiple distinct channels cleanly and rejects duplicate channels.
            stopped = base / "stop.request"
            stopped.touch()
            t2 = PaperTrainer(base / "run2", seed=7, rollout_size=32, maximum=32, max_bytes=8 * 1024 * 1024)
            serve([base / "c1", base / "c2"], t2, stopped)
            self.assertTrue((base / "c1").is_dir() and (base / "c2").is_dir())
            t3 = PaperTrainer(base / "run3", seed=7, rollout_size=32, maximum=32, max_bytes=8 * 1024 * 1024)
            try:
                with self.assertRaisesRegex(ValueError, "distinct"):
                    serve([base / "c1", base / "c1"], t3, stopped)
            finally:
                t3.close()

    def test_applied_state_computes_applied_value_across_pending_reload_and_rejects_invalid_vectors(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            trainer = PaperTrainer(root / "run1", seed=7, rollout_size=32, maximum=64, max_bytes=16 * 1024 * 1024)
            try:
                session_id = str(uuid.uuid4())
                t0 = row(trainer.model, session=session_id, sequence=0, terminal=False)
                t0.pop("nextValue")
                t0["appliedState"] = list(t0["frame"]["state"])
                trainer.handle({"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                                "frame": frame(), "transitions": [t0]})
                t1 = row(trainer.model, session=session_id, sequence=1, terminal=False)
                t1.pop("nextValue")
                t1["appliedTick"] = t0["appliedTick"] + t0["elapsedTicks"]
                t1["appliedState"] = list(t0["nextState"])
                trainer.handle({"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                                "frame": frame(), "transitions": [t1]})
                self.assertEqual(len(trainer.rows), 2)
                self.assertAlmostEqual(trainer.rows[1]["appliedValue"], trainer.rows[0]["nextValue"], places=12)
                for bad in ([0.0] * (STATE_SIZE - 1), [True] + [0.0] * (STATE_SIZE - 1),
                            [float("nan")] + [0.0] * (STATE_SIZE - 1), [16.5] + [0.0] * (STATE_SIZE - 1)):
                    bad_t = row(trainer.model, session=session_id, sequence=2, terminal=False)
                    bad_t.pop("nextValue")
                    bad_t["appliedState"] = bad
                    with self.assertRaises(ValueError):
                        validate_transition(bad_t)
            finally:
                trainer.close()
            restored_state = brain_session.load(root / "run1/runtime-session.json")
            restored = PaperTrainer(root / "run2", seed=99, rollout_size=32, maximum=64, max_bytes=16 * 1024 * 1024,
                                    resume=Path(restored_state["checkpoint"]), restore_state=restored_state)
            try:
                self.assertEqual(len(restored.rows), 2)
                self.assertAlmostEqual(restored.rows[1]["appliedValue"], restored.rows[0]["nextValue"], places=12)
            finally:
                restored.close()

    def test_reset_critic_preserves_actor_probabilities_and_clears_critic_saturation(self):
        model = ActorCritic(17, hidden=8)
        observed = frame()
        # Saturate critic hidden units while leaving actor weights nontrivial.
        model.fit([row(model)])
        model.params["value_w"].fill(5.0)
        model.params["value_b"].fill(3.0)
        model.params["value_bias"].fill(12.0)
        model.first["value_w"].fill(2.0)
        model.second["value_w"].fill(4.0)
        saturated_probs, _, (_, _, _, saturated_vh) = model.evaluate(observed)
        self.assertLess(float(np.mean(1.0 - saturated_vh ** 2)), 0.1)

        actor_snapshot = {k: model.params[k].copy() for k in ("actor_w", "actor_b", "actor_out", "actor_bias")}
        actor_first = {k: model.first[k].copy() for k in ("actor_w", "actor_b", "actor_out", "actor_bias")}
        actor_second = {k: model.second[k].copy() for k in ("actor_w", "actor_b", "actor_out", "actor_bias")}
        rng_before = model.rng.bit_generator.state
        counters_before = (model.version, model.updates)

        model.reset_critic(123)

        reset_probs, _, (_, _, _, reset_vh) = model.evaluate(observed)
        np.testing.assert_array_equal(reset_probs, saturated_probs)
        self.assertEqual((model.version, model.updates), counters_before)
        self.assertEqual(model.rng.bit_generator.state, rng_before)
        for k in actor_snapshot:
            np.testing.assert_array_equal(model.params[k], actor_snapshot[k])
            np.testing.assert_array_equal(model.first[k], actor_first[k])
            np.testing.assert_array_equal(model.second[k], actor_second[k])
        for k in ("value_w", "value_b", "value_out", "value_bias"):
            np.testing.assert_array_equal(model.first[k], np.zeros_like(model.params[k]))
            np.testing.assert_array_equal(model.second[k], np.zeros_like(model.params[k]))
        self.assertEqual(float(model.params["value_bias"][0]), 0.0)
        self.assertGreater(float(np.mean(1.0 - reset_vh ** 2)), 0.7)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaisesRegex(ValueError, "critic reset"):
                PaperTrainer(root / "fresh", seed=7, rollout_size=32, maximum=32, max_bytes=8 * 1024 * 1024, reset_critic=True)
            base = PaperTrainer(root / "base", seed=7, rollout_size=32, maximum=32, max_bytes=8 * 1024 * 1024)
            try:
                session = str(uuid.uuid4())
                for seq in range(32):
                    t = row(base.model, session=session, sequence=seq)
                    t.pop("nextValue")
                    base.handle({"schema": SCHEMA, "environment": "minecraft-paper", "id": str(uuid.uuid4()),
                                 "frame": frame(), "transitions": [t]})
                base.poll_training(wait=True)
            finally:
                base.close()
            with self.assertRaisesRegex(ValueError, "critic reset"):
                PaperTrainer(root / "off", seed=7, rollout_size=32, maximum=32, max_bytes=8 * 1024 * 1024,
                             resume=root / "base/optimizer-checkpoint.json", learning_enabled=False, reset_critic=True)

    def test_value_bias_tracks_return_shift_without_saturating_critic_hidden(self):
        model = ActorCritic(23, hidden=16)
        observed = frame()
        for _ in range(120):
            model.fit([row(model, reward=-10.0, terminal=True)])
        _, _, (_, _, _, vh) = model.evaluate(observed)
        self.assertLess(float(model.params["value_bias"][0]), -5.0)
        self.assertGreater(float(np.mean(1.0 - vh ** 2)), 0.5)

    def test_actor_weight_decay_shrinks_unused_columns_deterministically(self):
        model = ActorCritic(19, hidden=8)
        initial_actor_w = model.params["actor_w"].copy()
        zero_grads = {key: np.zeros_like(val) for key, val in model.params.items()}
        model.apply(zero_grads)
        np.testing.assert_allclose(model.params["actor_w"], initial_actor_w * 0.99995, rtol=0, atol=1e-12)
        self.assertTrue(np.isfinite(model.params["actor_w"]).all())
        model.version = 1
        restored = ActorCritic.from_optimizer_state(model.optimizer_state())
        np.testing.assert_array_equal(restored.params["actor_w"], model.params["actor_w"])


if __name__ == "__main__":
    unittest.main()
