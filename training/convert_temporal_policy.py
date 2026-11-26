"""Explicit separate-catalog extension of a trained native v7 actor.

Preserves all tensors/Adam/RNG/counters but changes probabilities for running
actions for temporal conversion. --goal-interaction instead adds zero cross
weights and preserves probabilities. --relative-entropy changes only the PPO
exploration objective while preserving inference and every saved tensor/RNG.
--primitive-relative estimates exploration against equal mass per primitive type.
None of these conversions is new learning or learned ability.
"""
import argparse,copy,hashlib,json
from pathlib import Path
import brain_session,checkpoint_store
from model_catalog import ModelCatalog,verify_state,model_name
from train_paper_ppo import resume_checkpoint
from continuation_policy import SELECTION, PRIMITIVE_RELATIVE_EXPLORATION
from rl_actor import ActorCritic, CONTRACT, ACTION_SIZE, STATE_ACTION_RANK

def convert(source: Path, output: Path, name: str, *, goal_interaction=False, state_action_interaction=False,
            relative_entropy=False, primitive_relative=False, seed: int = 20261126):
    if sum((goal_interaction, state_action_interaction, relative_entropy, primitive_relative)) > 1:
        raise ValueError('one explicit conversion at a time')
    changes_exploration = relative_entropy or primitive_relative
    state=brain_session.load(source);verify_state(state)
    if state['contract']!=CONTRACT or state.get('contextUpgrade') or state['initializationOnly'] or state['pendingRolloutTransitions']:
        raise ValueError('conversion requires trained native current-contract brain with no pending; originals preserved')
    if output.exists():raise ValueError('conversion output must be new')
    cp=Path(state['checkpoint']);model,parent=resume_checkpoint(cp)
    if not goal_interaction and not state_action_interaction and not changes_exploration and model.selection!='flat':raise ValueError('source must use flat selection')
    if (goal_interaction or state_action_interaction or changes_exploration) and (cp.parent.parent.parent/'conversion-evidence.json').exists():
        raise ValueError('materialized native training checkpoint required; conversion chains are not implicit resumes')
    checkpoint=json.loads(cp.read_bytes());report=json.loads((cp.parent/'training-report.json').read_bytes())
    before=copy.deepcopy(model.optimizer_state())
    if relative_entropy:
        if model.selection!=SELECTION or model.exploration!='categorical':raise ValueError('requires native temporal v2 with categorical entropy')
        model.exploration='continue-switch-relative/v1'
    elif primitive_relative:
        if model.exploration == PRIMITIVE_RELATIVE_EXPLORATION:
            raise ValueError('primitive-relative exploration is already active')
        model.exploration = PRIMITIVE_RELATIVE_EXPLORATION
    elif goal_interaction:model.enable_goal_interaction()
    elif state_action_interaction:model.enable_state_action_interaction(seed=seed)
    else:model.selection=SELECTION
    after=model.optimizer_state()
    if changes_exploration:
        unchanged = {key: value for key, value in before.items() if key != 'exploration'}
        if {key: value for key, value in after.items() if key != 'exploration'} != unchanged:
            raise ValueError('exploration conversion changed saved learning state')
    elif goal_interaction:ActorCritic.verify_zero_goal_extension(before,after)
    elif state_action_interaction:ActorCritic.verify_zero_state_action_extension(before,after)
    else:assert {k:v for k,v in after.items() if k!='selection'}==before
    raw=cp.parent.parent.parent/'paper-rollouts.jsonl' if report.get('rolloutFile') else cp.parent/'paper-rollouts.jsonl'
    payload=raw.read_bytes()[:report['rolloutBytesUsed']]
    if hashlib.sha256(payload).hexdigest()!=checkpoint['rolloutSha256']:raise ValueError('source prefix mismatch')
    output.mkdir(parents=True,exist_ok=False)
    checkpoint_store.durable_replace(output/'paper-rollouts.jsonl',payload)
    checkpoint['optimizer']=after
    artifact=model.artifact(environment_steps=state['trainedEnvironmentSteps'],training_run=checkpoint['run'],rollout_sha256=checkpoint['rolloutSha256'])
    artifact.update(brainId=state['brainId'],modelVersion=model.version,gradientUpdates=model.updates,trainingStartState='resume')
    bundle=checkpoint_store.publish(output,artifact,checkpoint,report)
    destination=bundle/'optimizer-checkpoint.json'
    migrated=dict(state,checkpoint=str(destination.resolve()),checkpointSha256=hashlib.sha256(destination.read_bytes()).hexdigest(),modelName=model_name(name))
    verify_state(migrated)
    brain_session.save(output/'runtime-session.json',migrated,recoverable=True)
    revision=ModelCatalog(output/'catalog').save(name,migrated)
    evidence={'schema':'minecraft-temporal-policy-conversion/v1','sourceSession':state,'convertedSession':migrated,'selection':SELECTION,'allTensorsAdamCheckpointRngAndCountersPreserved':True,'sessionRngPreserved':state['rng']==migrated['rng'],'initialProbabilitiesPreserved':False,'architecturePriorChanged':True,'newLearning':False,'abilityProven':False,'sourcePrefixSha256':hashlib.sha256(payload).hexdigest()}
    if goal_interaction:evidence.update(schema='minecraft-goal-interaction-conversion/v1',selection=model.selection,interaction=model.interaction,initialProbabilitiesPreserved=True,architecturePriorChanged=False,newZeroGoalActionTensorShape=[24,ACTION_SIZE])
    if state_action_interaction:evidence.update(schema='minecraft-state-action-interaction-conversion/v1',selection=model.selection,interaction=model.interaction,initialProbabilitiesPreserved=True,architecturePriorChanged=False,stateActionRank=STATE_ACTION_RANK,stateActionInitSeed=seed)
    if changes_exploration:evidence.update(schema='minecraft-ppo-exploration-conversion/v1',selection=model.selection,exploration=model.exploration,initialProbabilitiesPreserved=True,architecturePriorChanged=False,trainingObjectiveChanged=True)
    checkpoint_store.durable_replace(output/'conversion-evidence.json',checkpoint_store.encoded(evidence))
    return revision

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--name',type=model_name,required=True)
    parser.add_argument('--goal-interaction',action='store_true',help='zero-influence goal/action residual; preserve existing selection law')
    parser.add_argument('--state-action-interaction',action='store_true',help='zero-residual low-rank state-action conditioning; preserve existing selection law and probabilities')
    parser.add_argument('--seed',type=int,default=20261126,help='seed for state-action projection initialization')
    parser.add_argument('--relative-entropy',action='store_true',help='change only PPO exploration regularization; preserve actor probabilities and all learning state')
    parser.add_argument('--primitive-relative', action='store_true',
                        help='balance PPO exploration by primitive type; preserve inference and saved weights')
    args=parser.parse_args()
    print(json.dumps(convert(args.session, args.output, args.name, goal_interaction=args.goal_interaction,
                             state_action_interaction=args.state_action_interaction, seed=args.seed,
                             relative_entropy=args.relative_entropy, primitive_relative=args.primitive_relative)))
